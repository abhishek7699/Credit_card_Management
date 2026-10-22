#!/usr/bin/env bash
# Brings the full Credit Card Payment Gateway stack up on a local kind cluster --
# a rehearsal for running this on GKE. Builds all 7 service images, loads them
# into the cluster (no registry involved), applies every k8s/ manifest in
# dependency order, and waits until every pod is Ready.
#
# Prerequisites: kind, kubectl, docker, a JDK (via ./gradlew).
#
# Usage: scripts/k8s-up.sh
# Tear down with: scripts/k8s-down.sh

set -euo pipefail
IFS=$'\n\t'

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLUSTER_NAME="credit-card-kind"
NAMESPACE="credit-card"
SERVICES=(transaction-service authorization-service fraud-detection-service notification-service settlement-service reporting-service api-gateway)

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m'

info() { echo -e "${YELLOW}[INFO]${NC} $1"; }
pass() { echo -e "${GREEN}[OK]${NC} $1"; }
fail() { echo -e "${RED}[FAIL]${NC} $1"; exit 1; }

# ── Prerequisites ─────────────────────────────────────────────────────────────
for cmd in kind kubectl docker; do
  command -v "$cmd" &>/dev/null || fail "Missing prerequisite: $cmd"
done

# ── Load dev env vars (DB_USERNAME, DB_PASSWORD, VAULT_DEV_ROOT_TOKEN) ────────
if [[ -f "${REPO_ROOT}/.env" ]]; then
  info "Loading ${REPO_ROOT}/.env"
  set -a
  source "${REPO_ROOT}/.env"
  set +a
elif [[ -f "${REPO_ROOT}/.env.example" ]]; then
  info ".env not found -- falling back to .env.example (safe dev-only defaults, see SECURITY.md)"
  set -a
  source "${REPO_ROOT}/.env.example"
  set +a
else
  fail "Neither .env nor .env.example found at ${REPO_ROOT}"
fi
: "${DB_USERNAME:?}" "${DB_PASSWORD:-}" "${VAULT_DEV_ROOT_TOKEN:?}"

# ── Create the kind cluster if it doesn't already exist ──────────────────────
if kind get clusters 2>/dev/null | grep -qx "${CLUSTER_NAME}"; then
  info "kind cluster '${CLUSTER_NAME}' already exists, reusing it"
else
  info "Creating kind cluster '${CLUSTER_NAME}'"
  kind create cluster --name "${CLUSTER_NAME}"
fi
kubectl config use-context "kind-${CLUSTER_NAME}" >/dev/null

# ── Build jars (bootJar only -- skips the test/jacoco chain entirely; this
#    script is about deployment mechanics, not re-verifying correctness that
#    the test suite / CI already covers) ──────────────────────────────────────
info "Building service jars (./gradlew bootJar)"
cd "${REPO_ROOT}"
./gradlew bootJar --console=plain

# ── Build + load each service image ───────────────────────────────────────────
for svc in "${SERVICES[@]}"; do
  info "Building image credit-card/${svc}:local"
  docker build -q -f "services/${svc}/Dockerfile" -t "credit-card/${svc}:local" . >/dev/null
  info "Loading credit-card/${svc}:local into kind"
  kind load docker-image "credit-card/${svc}:local" --name "${CLUSTER_NAME}"
done
pass "All 7 images built and loaded"

# ── Namespace ──────────────────────────────────────────────────────────────────
kubectl apply -f "${REPO_ROOT}/k8s/namespace.yaml"

# ── Secrets (generated here, never committed -- same values as .env.example) ──
info "Generating Secrets from DB_USERNAME/DB_PASSWORD/VAULT_DEV_ROOT_TOKEN"

kubectl create secret generic vault-secrets -n "${NAMESPACE}" \
  --from-literal=VAULT_DEV_ROOT_TOKEN="${VAULT_DEV_ROOT_TOKEN}" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl create secret generic transaction-service-secrets -n "${NAMESPACE}" \
  --from-literal=datasource-url="jdbc:postgresql://cockroachdb:26257/defaultdb?sslmode=disable&currentSchema=transaction_service" \
  --from-literal=datasource-username="${DB_USERNAME}" \
  --from-literal=datasource-password="${DB_PASSWORD}" \
  --from-literal=kafka-bootstrap-servers="kafka:9092" \
  --from-literal=vault-addr="http://vault:8200" \
  --from-literal=vault-host="vault" \
  --from-literal=vault-port="8200" \
  --from-literal=vault-scheme="http" \
  --from-literal=vault-token="${VAULT_DEV_ROOT_TOKEN}" \
  --dry-run=client -o yaml | kubectl apply -f -

for svc in authorization-service fraud-detection-service notification-service; do
  schema="${svc//-/_}"
  kubectl create secret generic "${svc}-secrets" -n "${NAMESPACE}" \
    --from-literal=SPRING_DATASOURCE_URL="jdbc:postgresql://cockroachdb:26257/defaultdb?sslmode=disable&currentSchema=${schema}" \
    --from-literal=SPRING_DATASOURCE_USERNAME="${DB_USERNAME}" \
    --from-literal=SPRING_DATASOURCE_PASSWORD="${DB_PASSWORD}" \
    --from-literal=SPRING_KAFKA_BOOTSTRAP_SERVERS="kafka:9092" \
    --dry-run=client -o yaml | kubectl apply -f -
done

kubectl create secret generic settlement-service-secrets -n "${NAMESPACE}" \
  --from-literal=SPRING_DATASOURCE_URL="jdbc:postgresql://cockroachdb:26257/defaultdb?sslmode=disable&currentSchema=settlement_service" \
  --from-literal=SPRING_DATASOURCE_USERNAME="${DB_USERNAME}" \
  --from-literal=SPRING_DATASOURCE_PASSWORD="${DB_PASSWORD}" \
  --from-literal=SPRING_KAFKA_BOOTSTRAP_SERVERS="kafka:9092" \
  --dry-run=client -o yaml | kubectl apply -f -

# reporting-service reads settlement-service's own schema (no Flyway of its own)
kubectl create secret generic reporting-service-secrets -n "${NAMESPACE}" \
  --from-literal=SPRING_DATASOURCE_URL="jdbc:postgresql://cockroachdb:26257/defaultdb?sslmode=disable&currentSchema=settlement_service" \
  --from-literal=SPRING_DATASOURCE_USERNAME="${DB_USERNAME}" \
  --from-literal=SPRING_DATASOURCE_PASSWORD="${DB_PASSWORD}" \
  --dry-run=client -o yaml | kubectl apply -f -

# AUTH_SERVICE_JWKS_URI points at the in-cluster auth-mock Service
# (k8s/infra/auth-mock.yaml), not a host-side Docker container reached via
# host.docker.internal. That approach was tried first and is fundamentally
# broken for repeat test runs: the mock's RSA keypair was regenerated on every
# scripts/e2e-test.sh invocation, but api-gateway's JwtDecoder caches the JWKS
# it fetches -- any run after the first against an already-warm gateway pod
# failed JWT signature validation against the stale cached key (confirmed via
# Spring Security DEBUG logs: BadJWSException: Invalid signature). The
# in-cluster mock generates its keypair once at pod startup and keeps serving
# the same one for its whole lifetime, matching the cluster's lifecycle --
# this is what actually fixes it, not just where the mock happens to run.
kubectl create secret generic api-gateway-secrets -n "${NAMESPACE}" \
  --from-literal=REDIS_HOST="redis" \
  --from-literal=REDIS_PORT="6379" \
  --from-literal=AUTH_SERVICE_JWKS_URI="http://auth-mock:9000/.well-known/jwks.json" \
  --from-literal=TRANSACTION_SERVICE_GRPC_ADDRESS="static://transaction-service:9090" \
  --from-literal=REPORTING_SERVICE_URL="http://reporting-service:8086" \
  --dry-run=client -o yaml | kubectl apply -f -

pass "Secrets applied"

# ── Infra, then wait for it ────────────────────────────────────────────────────
info "Applying infra manifests"
kubectl apply -f "${REPO_ROOT}/k8s/infra/"
info "Waiting for infra pods to be Ready"
kubectl wait --for=condition=ready pod -l tier=infra -n "${NAMESPACE}" --timeout=240s
pass "Infra Ready"

# ── App services ───────────────────────────────────────────────────────────────
info "Applying service manifests"
for svc in "${SERVICES[@]}"; do
  kubectl apply -f "${REPO_ROOT}/k8s/${svc}/"
done

info "Waiting for every pod in namespace '${NAMESPACE}' to be Ready (this can take a few minutes)"
kubectl wait --for=condition=ready pod --all -n "${NAMESPACE}" --timeout=300s
pass "All pods Ready"

echo ""
echo -e "${BOLD}Stack is up.${NC} kubectl get pods -n ${NAMESPACE}"
echo ""
echo "To run the e2e suite against it:"
echo "  ./scripts/e2e-test.sh --target kind"
echo ""
echo "To poke at api-gateway directly:"
echo "  kubectl port-forward -n ${NAMESPACE} svc/api-gateway 8087:8080 8088:8090"
echo ""
echo "Tear down with: ./scripts/k8s-down.sh"
