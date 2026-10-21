# Security notes

This repo runs entirely in local-development / CI configuration. A few pieces that make
that convenient are **not safe to run as-is anywhere else** — if you're adapting this stack
for a real deployment, replace all three before doing anything else.

## 1. Vault dev mode

`docker-compose.yml`'s `vault` service runs `vault server -dev`: an in-memory,
pre-unsealed server with a single well-known root token, no TLS, no audit logging, and no
high availability. It exists solely so `transaction-service`'s card tokenization has a
Transit secrets engine to call locally and in CI. Every credit card number it "protects" in
this environment is a test fixture (see `scripts/e2e-test.sh`'s `4111111111111111`), and the
dev server (and all keys in it) is destroyed every time the container restarts.

**Never** point this compose stack at a real Vault cluster using this configuration, and
never reuse `vault server -dev` for anything that holds real data.

## 2. Mock JWT issuer

`scripts/e2e-test.sh`'s `setup_auth_mock` function stands in for the real Spring
Authorization Server api-gateway is designed to validate JWTs against: it generates a fresh
RSA keypair with `openssl genrsa` on every run, serves a JWKS document from a bare Python
`http.server` container named `auth-service`, and hand-signs test JWTs against that
throwaway key. The keypair is deleted in `cleanup()` at the end of every run.

This only exists to drive the end-to-end test without a real identity provider running. It
has no password/MFA/session logic, accepts any subject or role claim the test script asks
for, and must never be exposed outside the e2e harness.

## 3. Frontend PIN login

`frontend/src/features/auth/{AuthGate,PinRegistration,PinUnlock,hashPin,deviceStorage}.tsx`
implement a "remember this device" convenience gate, not authentication:

- A 4-digit PIN (10,000 possible values) is hashed with **unsalted SHA-256** and stored in
  plaintext in `localStorage`.
- Unlocking re-hashes the entered PIN and compares it **entirely in the browser** — no
  network call is made, and nothing server-side is ever consulted.
- It produces no token, cookie, or credential that api-gateway's actual RS256/JWKS-based
  authentication would accept; the frontend never sends an `Authorization` header derived
  from this flow.

Anyone with access to the browser's `localStorage` can read or delete the stored PIN hash
(bypassing it entirely) or brute-force the unsalted hash offline in well under a second. This
is UX polish for a single device, not an access-control boundary — don't mistake it for one,
and don't build real authorization decisions on top of it.

## Local environment variables

`docker-compose.yml` reads `DB_USERNAME`, `DB_PASSWORD`, and `VAULT_DEV_ROOT_TOKEN` from a
`.env` file (see `.env.example` — copy it to `.env`, which is gitignored). These are **not**
secrets worth protecting on their own: CockroachDB runs with `--insecure` and doesn't check
credentials at all, and Vault dev mode just needs every service to agree on the same token
string (any non-empty value works). They're kept out of version control anyway so that this
repo doesn't become a template for hardcoding real credentials the same way in a different
context. CI supplies the same values directly in `.github/workflows/ci.yml`'s `env:` block
for the same reason — they're dev-only fixtures, not production secrets requiring GitHub
Actions Secrets.
