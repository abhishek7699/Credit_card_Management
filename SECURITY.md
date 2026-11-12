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

There are **two** separate, unrelated things standing in for the real Spring
Authorization Server api-gateway is designed to validate JWTs against — neither is a
real identity provider, and neither must ever be exposed outside local dev/CI:

- `scripts/e2e-test.sh`'s `setup_auth_mock` function generates a fresh RSA keypair
  with `openssl genrsa` on the host at the start of every e2e run, serves a JWKS
  document from a bare Python `http.server` container named `auth-service`, and
  hand-signs test JWTs **locally in the script itself** (never over HTTP) against
  that throwaway key. The keypair and the container are both deleted in `cleanup()`
  at the end of every run. This exists only to drive the end-to-end test.
- `docker-compose.yml`'s `auth-mock` service (`scripts/dev-auth-mock.py`, also named
  `auth-service`) is a **standing** version of the same idea, for the staff
  frontend's dev-only login screen (layer 2d): it generates its own RSA keypair when
  the container starts, serves the same JWKS shape, and exposes `POST /dev/login`
  — given `{"role": "FRONT_DESK"|"HOUSEKEEPING"}`, it signs and returns a JWT for
  that role with **no password, credential, or identity check of any kind**. It
  stays up for as long as the compose stack does; the e2e script's own container
  (above) force-recreates/removes anything named `auth-service` regardless, so the
  two never actually run at the same time despite sharing a name.

Both accept any role claim asked of them and must never be reachable from anywhere
but a developer's own machine or CI.

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
