#!/usr/bin/env bash
# One real, OAuth-signed Account Validation call to the Mastercard Developers SANDBOX.
# Manual only, never run in CI. Prints the HTTP status and response body, nothing secret.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Load .env if present (same checkout). Values are exported, never echoed.
if [ -f "$ROOT/.env" ]; then set -a; . "$ROOT/.env"; set +a; fi

missing=0
for v in MASTERCARD_DEV_CONSUMER_KEY MASTERCARD_DEV_KEY_PATH MASTERCARD_DEV_KEY_ALIAS MASTERCARD_DEV_KEYSTORE_PASSWORD; do
  if [ -z "${!v:-}" ]; then echo "Missing required variable: $v" >&2; missing=1; fi
done
[ "$missing" -eq 0 ] || { echo "Refusing to run." >&2; exit 2; }
[ -f "$MASTERCARD_DEV_KEY_PATH" ] || { echo "MASTERCARD_DEV_KEY_PATH does not point to a file." >&2; exit 2; }

cd "$ROOT"
exec ./gradlew -q :libs:mastercard-developers:smoke
