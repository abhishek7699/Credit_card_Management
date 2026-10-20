#!/usr/bin/env bash
# End-to-end integration test for the Credit Card Payment Gateway.
# Prerequisites: docker, docker compose v2, grpcurl, curl, python3, openssl

set -euo pipefail
IFS=$'\n\t'

# ── Ports ─────────────────────────────────────────────────────────────────────
GW_PORT=8087
GW_MGMT=8088
TXN_GRPC=9090
TXN_MGMT=8081
AUTH_MGMT=8082
SETTLE_MGMT=8085
REPORT_PORT=8086
REPORT_MGMT=8091

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${REPO_ROOT}/docker-compose.yml"
PROTO_DIR="${REPO_ROOT}/proto"

AUTH_CONTAINER="auth-service"
AUTH_MOCK_PORT=9000
AUTH_NETWORK="credit_card_default"   # adjust if compose project name differs

# ── Colour helpers ────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m'

pass() { echo -e "${GREEN}[PASS]${NC} $1"; RESULTS+=("PASS  | $1"); }
fail() { echo -e "${RED}[FAIL]${NC} $1"; RESULTS+=("FAIL  | $1"); FAILED=1; }
info() { echo -e "${YELLOW}[INFO]${NC} $1"; }

RESULTS=()
FAILED=0

# ── Prerequisite check ────────────────────────────────────────────────────────
check_prereqs() {
  local missing=()
  for cmd in docker grpcurl curl python3 openssl; do
    command -v "$cmd" &>/dev/null || missing+=("$cmd")
  done
  if [[ ${#missing[@]} -gt 0 ]]; then
    echo -e "${RED}Missing prerequisites: ${missing[*]}${NC}"
    exit 1
  fi
  # grpcurl version sanity
  grpcurl -version 2>&1 | head -1 || true
}

# ── Step 1: Start services ─────────────────────────────────────────────────────
step1_start_services() {
  info "Step 1 — Starting all services via Docker Compose"
  cd "${REPO_ROOT}"
  docker compose -f "${COMPOSE_FILE}" up -d --build 2>&1 | tail -5
  pass "Step 1 — Docker Compose services started"
}

# ── Step 2: Wait for health ───────────────────────────────────────────────────
wait_http() {
  local name=$1 url=$2 max=${3:-120} interval=3 elapsed=0
  info "Waiting for ${name} at ${url}"
  while true; do
    local code
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "${url}" 2>/dev/null || true)
    [[ "$code" =~ ^(200|204)$ ]] && return 0
    elapsed=$((elapsed + interval))
    [[ $elapsed -ge $max ]] && return 1
    sleep "$interval"
  done
}

step2_health_checks() {
  info "Step 2 — Waiting for all services to be healthy"
  local ok=1

  wait_http "api-gateway"          "http://localhost:${GW_MGMT}/actuator/health"     180 || { fail "Step 2 — api-gateway unhealthy";          ok=0; }
  wait_http "transaction-service"  "http://localhost:${TXN_MGMT}/actuator/health"    180 || { fail "Step 2 — transaction-service unhealthy";   ok=0; }
  wait_http "reporting-service"    "http://localhost:${REPORT_MGMT}/actuator/health" 180 || { fail "Step 2 — reporting-service unhealthy";     ok=0; }
  wait_http "settlement-service"   "http://localhost:${SETTLE_MGMT}/actuator/health" 180 || { fail "Step 2 — settlement-service unhealthy";    ok=0; }

  [[ $ok -eq 1 ]] && pass "Step 2 — All services healthy"
}

# ── Card-account seed ─────────────────────────────────────────────────────────
# authorization-service authorizes by looking up card_accounts by the Vault card
# token. The transit key is convergent+derived, so encrypting the test PAN with
# the same context that transaction-service uses yields the exact token that will
# be on the event — seed a matching ACTIVE row with ample credit.
CARD_PAN="4111111111111111"
CARD_CONTEXT="credit-card-pan"

seed_card_account() {
  info "Seeding authorization_service.card_accounts for the e2e test card"

  local pan_b64 ctx_b64 ciphertext
  pan_b64=$(printf '%s' "${CARD_PAN}" | base64 | tr -d '\n')
  ctx_b64=$(printf '%s' "${CARD_CONTEXT}" | base64 | tr -d '\n')

  ciphertext=$(curl -sf -X POST \
    -H "X-Vault-Token: dev-root-token" \
    -d "{\"plaintext\":\"${pan_b64}\",\"context\":\"${ctx_b64}\"}" \
    "http://localhost:8200/v1/transit/encrypt/card-pan-key" 2>/dev/null \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['ciphertext'])" 2>/dev/null || echo "")

  if [[ -z "$ciphertext" ]]; then
    fail "Card seed — could not obtain a deterministic card token from Vault"
    return 1
  fi

  docker compose -f "${COMPOSE_FILE}" exec -T cockroachdb \
    cockroach sql --insecure --database=defaultdb -e \
    "INSERT INTO authorization_service.card_accounts
       (card_token, status, credit_limit, available_credit, currency_code)
     VALUES ('${ciphertext}', 'ACTIVE', 100000.0000, 100000.0000, 'USD')
     ON CONFLICT (card_token) DO NOTHING;" >/dev/null

  info "Card account seeded (token ${ciphertext:0:20}…)"
}

# ── Auth-service mock ─────────────────────────────────────────────────────────
# Generates an RSA key pair, serves JWKS on port 9000, and issues JWTs.
# The api-gateway's application.yml must point jwk-set-uri to http://auth-service:9000/oauth2/jwks
# (which resolves inside the compose network to this container).

setup_auth_mock() {
  info "Setting up auth-service mock (RSA JWKS + JWT issuer)"

  # Generate RSA key pair on the host
  local key_dir
  key_dir=$(mktemp -d)
  openssl genrsa -out "${key_dir}/private.pem" 2048 2>/dev/null
  openssl rsa -in "${key_dir}/private.pem" -pubout -out "${key_dir}/public.pem" 2>/dev/null

  # Build a minimal JWKS from the RSA key. The `cryptography` package is not
  # present on the host (nor on the CI runner), so run the generation inside a
  # throwaway Python container with `cryptography` pip-installed. This keeps the
  # harness hermetic — no host pip state — and behaves identically on developer
  # machines (incl. PEP-668 macOS) and CI. `cryptography` ships manylinux
  # wheels, so the install is download-only (~5-10 s), no toolchain required.
  cat > "${key_dir}/gen_jwks.py" <<'PYEOF'
import sys, json, base64
from cryptography.hazmat.primitives.serialization import load_pem_private_key

pem_path = sys.argv[1]
out_dir  = sys.argv[2]

with open(pem_path, "rb") as f:
    priv = load_pem_private_key(f.read(), password=None)

pub_nums = priv.public_key().public_numbers()

def to_b64(n):
    raw = n.to_bytes((n.bit_length() + 7) // 8, "big")
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()

jwk = {
    "kty": "RSA",
    "kid": "e2e-key-1",
    "use": "sig",
    "alg": "RS256",
    "n": to_b64(pub_nums.n),
    "e": to_b64(pub_nums.e),
}

with open(f"{out_dir}/jwks.json", "w") as f:
    json.dump({"keys": [jwk]}, f)
print("JWKS written")
PYEOF

  docker run --rm -v "${key_dir}:/data" python:3.11-slim \
    sh -c 'pip install --quiet --disable-pip-version-check --root-user-action=ignore cryptography \
           && python3 /data/gen_jwks.py /data/private.pem /data'

  if [[ ! -f "${key_dir}/jwks.json" ]]; then
    fail "Auth-service mock — JWKS generation failed"
    return 1
  fi

  # Store key path for JWT generation later
  E2E_KEY_DIR="${key_dir}"
  export E2E_KEY_DIR

  # Stop any existing mock
  docker rm -f "${AUTH_CONTAINER}" 2>/dev/null || true

  # Start a Python HTTP server container serving the JWKS
  docker run -d \
    --name "${AUTH_CONTAINER}" \
    --network "${AUTH_NETWORK}" \
    -p "${AUTH_MOCK_PORT}:9000" \
    -v "${key_dir}:/data:ro" \
    python:3.11-slim \
    python3 -c "
import http.server, json, pathlib, os

class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        jwks = pathlib.Path('/data/jwks.json').read_text()
        if self.path in ('/oauth2/jwks', '/.well-known/jwks.json'):
            body = jwks.encode()
        elif self.path == '/.well-known/openid-configuration':
            body = json.dumps({'issuer':'http://auth-service:9000','jwks_uri':'http://auth-service:9000/oauth2/jwks'}).encode()
        else:
            self.send_response(404); self.end_headers(); return
        self.send_response(200)
        self.send_header('Content-Type','application/json')
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, *a): pass

http.server.HTTPServer(('0.0.0.0', 9000), H).serve_forever()
" 2>/dev/null

  sleep 2

  # Verify JWKS is reachable
  local jwks
  jwks=$(curl -sf "http://localhost:${AUTH_MOCK_PORT}/oauth2/jwks" 2>/dev/null || echo "")
  if [[ -z "$jwks" ]]; then
    fail "Auth-service mock JWKS not reachable"
    return 1
  fi
  info "Auth mock JWKS reachable"
}

# ── Step 3: Obtain JWT ────────────────────────────────────────────────────────
make_jwt() {
  local sub=$1 role=$2 merchant_id=${3:-}
  python3 - "${E2E_KEY_DIR}/private.pem" "$sub" "$role" "$merchant_id" <<'PYEOF'
import sys, json, base64, time, hashlib, subprocess

key_file    = sys.argv[1]
sub         = sys.argv[2]
role        = sys.argv[3]
merchant_id = sys.argv[4]

def b64url(data):
    if isinstance(data, str):
        data = data.encode()
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()

header = {"alg":"RS256","typ":"JWT","kid":"e2e-key-1"}
now = int(time.time())
payload = {
    "sub": sub,
    "iss": "http://auth-service:9000",
    "iat": now,
    "exp": now + 3600,
    "authorities": [role],
}
if merchant_id:
    payload["merchantId"] = merchant_id

h = b64url(json.dumps(header, separators=(",",":")))
p = b64url(json.dumps(payload, separators=(",",":")))
msg = f"{h}.{p}"

# Sign with openssl dgst
import tempfile, os
with tempfile.NamedTemporaryFile(delete=False, suffix=".txt") as tf:
    tf.write(msg.encode())
    tf_path = tf.name

sig_bytes = subprocess.check_output(
    ["openssl", "dgst", "-sha256", "-sign", key_file, tf_path],
    stderr=subprocess.DEVNULL
)
os.unlink(tf_path)
sig = base64.urlsafe_b64encode(sig_bytes).rstrip(b"=").decode()
print(f"{h}.{p}.{sig}")
PYEOF
}

step3_obtain_jwt() {
  info "Step 3 — Obtaining JWT from auth-service mock"
  MERCHANT_JWT=$(make_jwt "merchant-e2e-001" "ROLE_MERCHANT" "merchant-e2e-001")
  ADMIN_JWT=$(make_jwt "admin-e2e-001" "ROLE_ADMIN" "")

  if [[ -z "$MERCHANT_JWT" || -z "$ADMIN_JWT" ]]; then
    fail "Step 3 — JWT generation failed"
    return 1
  fi
  export MERCHANT_JWT ADMIN_JWT
  pass "Step 3 — JWT tokens obtained"
}

# ── Step 4: Submit payment ────────────────────────────────────────────────────
step4_submit_payment() {
  info "Step 4 — Submitting payment request to api-gateway"

  local resp http_code body
  resp=$(curl -s -w '\n%{http_code}' \
    -X POST "http://localhost:${GW_PORT}/api/v1/transactions" \
    -H "Authorization: Bearer ${MERCHANT_JWT}" \
    -H "Content-Type: application/json" \
    -d '{"cardNumber":"4111111111111111","cardExpiry":"12/27","cvv":"123","cardholderName":"E2E Test","amount":"99.99","currencyCode":"USD"}' \
    2>/dev/null)

  http_code=$(echo "$resp" | tail -1)
  body=$(echo "$resp" | sed '$d')

  if [[ "$http_code" != "201" ]]; then
    fail "Step 4 — Expected 201, got ${http_code}. Body: ${body}"
    echo ""
    info "── Diagnostic logs (api-gateway + transaction-service, last 150 lines) ──"
    docker compose -f "${COMPOSE_FILE}" logs api-gateway transaction-service --tail=150
    return 1
  fi

  TXN_ID=$(echo "$body" | python3 -c "import sys,json; print(json.load(sys.stdin).get('transactionId',''))" 2>/dev/null || echo "")
  if [[ -z "$TXN_ID" ]]; then
    fail "Step 4 — No transactionId in response: ${body}"
    return 1
  fi

  export TXN_ID
  pass "Step 4 — Payment submitted (transactionId=${TXN_ID})"
}

# ── Step 5: Poll status ───────────────────────────────────────────────────────
step5_poll_status() {
  info "Step 5 — Polling transaction status (${TXN_ID})"
  local max=60 elapsed=0 interval=3 status=""

  while [[ $elapsed -lt $max ]]; do
    local resp http_code body
    resp=$(curl -s -w '\n%{http_code}' \
      "http://localhost:${GW_PORT}/api/v1/transactions/${TXN_ID}" \
      -H "Authorization: Bearer ${MERCHANT_JWT}" 2>/dev/null)
    http_code=$(echo "$resp" | tail -1)
    body=$(echo "$resp" | sed '$d')

    if [[ "$http_code" == "200" ]]; then
      status=$(echo "$body" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null || echo "")
      if [[ "$status" =~ ^(AUTHORIZED|DECLINED)$ ]]; then
        pass "Step 5 — Transaction status: ${status}"
        return 0
      fi
    fi

    elapsed=$((elapsed + interval))
    sleep "$interval"
  done

  fail "Step 5 — Transaction did not reach terminal status within ${max}s (last status=${status})"
}

# ── Step 6: PAN scan ──────────────────────────────────────────────────────────
step6_pan_scan() {
  info "Step 6 — Scanning all service logs for raw PAN"
  local raw_pan="4111111111111111"
  local found=0

  # Fetch logs for each service defined in compose
  for svc in api-gateway transaction-service authorization-service fraud-detection-service notification-service settlement-service reporting-service; do
    local logs
    logs=$(docker compose -f "${COMPOSE_FILE}" logs --no-color "${svc}" 2>/dev/null || true)
    if echo "$logs" | grep -qF "$raw_pan"; then
      fail "Step 6 — Raw PAN found in logs for ${svc}"
      found=1
    fi
  done

  [[ $found -eq 0 ]] && pass "Step 6 — No raw PAN found in any service logs"
}

# ── Step 7: Rate limit test ───────────────────────────────────────────────────
step7_rate_limit() {
  info "Step 7 — Rate limit test (burst-capacity=20, sending 100 requests)"

  local -a pids=() codes=()
  local tmp_dir
  tmp_dir=$(mktemp -d)

  # Send 100 concurrent requests. Burst capacity is only 20, but the
  # RedisRateLimiter is a continuously-refilling token bucket (replenish
  # rate 10/sec), so on a slow/shared CI runner the server-side processing
  # of a small burst can spread out enough for replenishment to cover a
  # thin (1-request) deficit and let everything through with no 429s.
  # Sending far more than the burst capacity keeps the test robust to that
  # timing variance without touching the actual rate limiter config.
  for i in $(seq 1 100); do
    {
      code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 \
        "http://localhost:${GW_PORT}/api/v1/transactions/${TXN_ID}" \
        -H "Authorization: Bearer ${MERCHANT_JWT}" 2>/dev/null || echo "000")
      echo "$code" > "${tmp_dir}/${i}.code"
    } &
    pids+=($!)
  done
  for pid in "${pids[@]}"; do wait "$pid" 2>/dev/null || true; done

  local count_429=0
  for f in "${tmp_dir}"/*.code; do
    code=$(cat "$f")
    [[ "$code" == "429" ]] && count_429=$((count_429 + 1))
  done
  rm -rf "${tmp_dir}"

  if [[ $count_429 -ge 1 ]]; then
    pass "Step 7 — Rate limiting triggered (${count_429} × 429 responses)"
  else
    fail "Step 7 — No 429 responses received (burst-capacity=20, sent 100 requests)"
  fi
}

# ── Step 8: Circuit breaker ───────────────────────────────────────────────────
step8_circuit_breaker() {
  info "Step 8 — Circuit breaker test (stopping transaction-service)"

  docker compose -f "${COMPOSE_FILE}" stop transaction-service 2>/dev/null

  local tripped=0
  for i in $(seq 1 15); do
    local resp http_code body
    resp=$(curl -s -w '\n%{http_code}' --max-time 5 \
      -X POST "http://localhost:${GW_PORT}/api/v1/transactions" \
      -H "Authorization: Bearer ${MERCHANT_JWT}" \
      -H "Content-Type: application/json" \
      -d '{"cardNumber":"4111111111111111","cardExpiry":"12/27","cvv":"123","cardholderName":"CB Test","amount":"1.00","currencyCode":"USD"}' \
      2>/dev/null)
    http_code=$(echo "$resp" | tail -1)
    body=$(echo "$resp" | sed '$d')

    if [[ "$http_code" == "503" ]]; then
      # Verify fallback body
      local err_field
      err_field=$(echo "$body" | python3 -c "import sys,json; print(json.load(sys.stdin).get('error',''))" 2>/dev/null || echo "")
      if [[ "$err_field" == "Service Unavailable" ]]; then
        tripped=1
        break
      fi
    fi
    sleep 2
  done

  # Restart transaction-service for subsequent steps
  docker compose -f "${COMPOSE_FILE}" start transaction-service 2>/dev/null
  wait_http "transaction-service" "http://localhost:${TXN_MGMT}/actuator/health" 90 || true

  if [[ $tripped -eq 1 ]]; then
    pass "Step 8 — Circuit breaker opened; fallback 503 received"
  else
    fail "Step 8 — No 503 fallback received after stopping transaction-service"
  fi
}

# ── Step 9: Reporting query ───────────────────────────────────────────────────
step9_reporting_query() {
  info "Step 9 — Querying reporting-service for transaction ${TXN_ID}"

  local resp http_code body
  resp=$(curl -s -w '\n%{http_code}' \
    "http://localhost:${REPORT_PORT}/internal/reporting/transactions/${TXN_ID}" \
    -H "Authorization: Bearer ${ADMIN_JWT}" 2>/dev/null)
  http_code=$(echo "$resp" | tail -1)
  body=$(echo "$resp" | sed '$d')

  if [[ "$http_code" == "200" ]]; then
    # Verify masked card number (no raw PAN)
    local masked
    masked=$(echo "$body" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('maskedCardNumber',''))" 2>/dev/null || echo "")
    if echo "$masked" | grep -qF "4111111111111111"; then
      fail "Step 9 — Raw PAN present in reporting response"
    else
      pass "Step 9 — Reporting query OK (maskedCardNumber=${masked})"
    fi
  else
    fail "Step 9 — Reporting query returned ${http_code}. Body: ${body}"
  fi
}

# ── Step 10: Settlement trigger ───────────────────────────────────────────────
step10_settlement() {
  info "Step 10 — Triggering settlement batch and verifying SETTLED status"

  # settlement-service internal endpoint; accessed via docker exec to avoid host port conflict
  local trigger_resp
  trigger_resp=$(docker compose -f "${COMPOSE_FILE}" exec -T settlement-service \
    curl -s -o /dev/null -w '%{http_code}' --max-time 10 \
    -X POST "http://localhost:8080/internal/settlement/trigger" 2>/dev/null || echo "000")

  if [[ ! "$trigger_resp" =~ ^(200|204|202)$ ]]; then
    fail "Step 10 — Settlement trigger returned ${trigger_resp}"
    return 1
  fi

  # Poll reporting-service for SETTLED status
  local max=90 elapsed=0 interval=5 final_status=""
  while [[ $elapsed -lt $max ]]; do
    local resp http_code body
    resp=$(curl -s -w '\n%{http_code}' \
      "http://localhost:${REPORT_PORT}/internal/reporting/transactions/${TXN_ID}" \
      -H "Authorization: Bearer ${ADMIN_JWT}" 2>/dev/null)
    http_code=$(echo "$resp" | tail -1)
    body=$(echo "$resp" | sed '$d')

    if [[ "$http_code" == "200" ]]; then
      final_status=$(echo "$body" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null || echo "")
      [[ "$final_status" == "SETTLED" ]] && { pass "Step 10 — Transaction SETTLED"; return 0; }
    fi
    elapsed=$((elapsed + interval))
    sleep "$interval"
  done

  fail "Step 10 — Transaction did not reach SETTLED within ${max}s (last status=${final_status})"
}

# ── Cleanup ───────────────────────────────────────────────────────────────────
cleanup() {
  info "Cleaning up auth-service mock container"
  docker rm -f "${AUTH_CONTAINER}" 2>/dev/null || true
  [[ -n "${E2E_KEY_DIR:-}" ]] && rm -rf "${E2E_KEY_DIR}"
}
trap cleanup EXIT

# ── Summary ───────────────────────────────────────────────────────────────────
print_summary() {
  echo ""
  echo -e "${BOLD}═══════════════════════════════════════════════════${NC}"
  echo -e "${BOLD}  End-to-End Test Summary${NC}"
  echo -e "${BOLD}═══════════════════════════════════════════════════${NC}"
  for r in "${RESULTS[@]}"; do
    if [[ "$r" == PASS* ]]; then
      echo -e "  ${GREEN}${r}${NC}"
    else
      echo -e "  ${RED}${r}${NC}"
    fi
  done
  echo -e "${BOLD}═══════════════════════════════════════════════════${NC}"
  if [[ $FAILED -eq 0 ]]; then
    echo -e "  ${GREEN}${BOLD}Overall: PASS${NC}"
  else
    echo -e "  ${RED}${BOLD}Overall: FAIL${NC}"
  fi
  echo -e "${BOLD}═══════════════════════════════════════════════════${NC}"
  return $FAILED
}

# ── Main ──────────────────────────────────────────────────────────────────────
main() {
  echo -e "${BOLD}Credit Card Payment Gateway — E2E Integration Tests${NC}"
  echo "Date: $(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  echo ""

  check_prereqs

  step1_start_services
  step2_health_checks

  # Allow 15 s for the transaction-service circuit breaker to recover.
  # grpc-spring-boot-starter probes the gRPC channel during startup; those
  # UNAVAILABLE errors accumulate as CB failures while transaction-service is
  # initialising. With waitDurationInOpenState=10s the CB moves to HALF_OPEN
  # automatically, so by the time Step 4 fires the first probe request
  # succeeds and the CB closes immediately.
  info "Waiting 15s for circuit breaker cooldown after service startup"
  sleep 15

  seed_card_account
  setup_auth_mock

  step3_obtain_jwt
  step4_submit_payment
  step5_poll_status
  step6_pan_scan
  step7_rate_limit
  step8_circuit_breaker
  step9_reporting_query
  step10_settlement

  print_summary
}

main "$@"
