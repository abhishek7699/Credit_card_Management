#!/usr/bin/env bash
# Builds all 7 service images, pushes them to Artifact Registry, and applies
# the full stack to a GKE cluster via k8s/overlays/gke (same manifests as
# the local kind rehearsal, replicas: 2, images from a real registry).
#
# Minimal by design: assumes the cluster, Artifact Registry repo, and
# kubectl context already exist (see docs/GKE.md for the exact gcloud
# commands) and that Secrets use the same dev-only values as the local
# stack (see SECURITY.md) -- this targets the $5/month spot-node demo
# cluster docs/GKE.md walks through, not a hardened production environment.
#
# Required env vars: GCP_PROJECT_ID
# Optional env vars: AR_REGION (default us-central1), AR_REPO (default
#   credit-card), TAG (default: current git short SHA)
#
# Prerequisites: gcloud (authenticated, kubectl context pointing at the
# target cluster -- `gcloud container clusters get-credentials`), docker,
# envsubst (gettext), a JDK (via ./gradlew).
#
# Usage: GCP_PROJECT_ID=my-project scripts/gke-deploy.sh
# Scale the cluster up/down for a demo: scripts/demo-up.sh / demo-down.sh

set -euo pipefail
IFS=$'\n\t'

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
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
for cmd in gcloud kubectl docker envsubst; do
  command -v "$cmd" &>/dev/null || fail "Missing prerequisite: $cmd"
done
: "${GCP_PROJECT_ID:?Set GCP_PROJECT_ID to your GCP project (see docs/GKE.md)}"
AR_REGION="${AR_REGION:-us-central1}"
AR_REPO="${AR_REPO:-credit-card}"
TAG="${TAG:-$(git -C "${REPO_ROOT}" rev-parse --short HEAD)}"
REGISTRY="${AR_REGION}-docker.pkg.dev/${GCP_PROJECT_ID}/${AR_REPO}"

info "Target: ${REGISTRY} (tag ${TAG}), namespace ${NAMESPACE}"

# ── Load dev env vars (DB_USERNAME, DB_PASSWORD, VAULT_DEV_ROOT_TOKEN) ────────
if [[ -f "${REPO_ROOT}/.env" ]]; then
  info "Loading ${REPO_ROOT}/.env"
  set -a; source "${REPO_ROOT}/.env"; set +a
elif [[ -f "${REPO_ROOT}/.env.example" ]]; then
  info ".env not found -- falling back to .env.example (dev-only defaults, see SECURITY.md)"
  set -a; source "${REPO_ROOT}/.env.example"; set +a
else
  fail "Neither .env nor .env.example found at ${REPO_ROOT}"
fi
: "${DB_USERNAME:?}" "${DB_PASSWORD:-}" "${VAULT_DEV_ROOT_TOKEN:?}"

# ── Build jars (bootJar only, same as scripts/k8s-up.sh) ──────────────────────
info "Building service jars (./gradlew bootJar)"
cd "${REPO_ROOT}"
./gradlew bootJar --console=plain

# ── Build + push each image to Artifact Registry ──────────────────────────────
gcloud auth configure-docker "${AR_REGION}-docker.pkg.dev" --quiet
for svc in "${SERVICES[@]}"; do
  info "Building and pushing ${REGISTRY}/${svc}:${TAG}"
  docker build -q -f "services/${svc}/Dockerfile" -t "${REGISTRY}/${svc}:${TAG}" . >/dev/null
  docker push -q "${REGISTRY}/${svc}:${TAG}" >/dev/null
done
pass "All 7 images built and pushed"

# ── Namespace ──────────────────────────────────────────────────────────────────
kubectl apply -f "${REPO_ROOT}/k8s/namespace.yaml"

# ── Secrets (generated here, never committed -- same values/shape as
#    scripts/k8s-up.sh, which is the source of truth for what each service
#    actually needs) ────────────────────────────────────────────────────────
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

kubectl create secret generic reporting-service-secrets -n "${NAMESPACE}" \
  --from-literal=SPRING_DATASOURCE_URL="jdbc:postgresql://cockroachdb:26257/defaultdb?sslmode=disable&currentSchema=settlement_service" \
  --from-literal=SPRING_DATASOURCE_USERNAME="${DB_USERNAME}" \
  --from-literal=SPRING_DATASOURCE_PASSWORD="${DB_PASSWORD}" \
  --dry-run=client -o yaml | kubectl apply -f -

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

# ── App services, via the gke overlay (replicas: 2, images from Artifact
#    Registry) ─────────────────────────────────────────────────────────────
info "Rendering k8s/overlays/gke/kustomization.yaml from its template"
GCP_PROJECT_ID="${GCP_PROJECT_ID}" AR_REGION="${AR_REGION}" AR_REPO="${AR_REPO}" TAG="${TAG}" \
  envsubst < "${REPO_ROOT}/k8s/overlays/gke/kustomization.yaml.tmpl" \
  > "${REPO_ROOT}/k8s/overlays/gke/kustomization.yaml"

info "Applying service manifests (k8s/overlays/gke)"
kubectl apply -k "${REPO_ROOT}/k8s/overlays/gke"

info "Waiting for every pod in namespace '${NAMESPACE}' to be Ready (this can take a few minutes)"
kubectl wait --for=condition=ready pod --all -n "${NAMESPACE}" --timeout=300s
pass "All pods Ready"

echo ""
echo -e "${BOLD}Stack is up on GKE.${NC} kubectl get pods -n ${NAMESPACE}"
echo ""
echo "Scale the node pool down between demos: ./scripts/demo-down.sh"
echo "Scale it back up: ./scripts/demo-up.sh"
