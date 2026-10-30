#!/usr/bin/env python3
"""Standing, dev-only JWT issuer for the staff frontend (layer 2d).

Not a real identity provider: it generates one RSA keypair when this process starts,
serves that key's JWKS so api-gateway can validate signatures, and signs a JWT for
whichever role POST /dev/login asks for with no password/credential check at all.

This is a *separate* thing from scripts/e2e-test.sh's own ephemeral auth-mock
container (which generates its own keypair on the host and signs tokens locally,
never over HTTP, for the duration of one e2e run only). That script is unmodified and
unaffected by this one — see specs/010-frontend-mvp/research.md (R0) for why both can
coexist safely. See SECURITY.md for why this must never be exposed outside local dev.
"""
import base64
import http.server
import json
import time
from http import HTTPStatus

from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives import hashes, serialization

ISSUER = "http://auth-service:9000"
KEY_ID = "dev-auth-mock-key-1"
TOKEN_TTL_SECONDS = 4 * 60 * 60  # 4h — long enough for a dev session, never persisted
ALLOWED_ROLES = ("FRONT_DESK", "HOUSEKEEPING")

_private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
_public_numbers = _private_key.public_key().public_numbers()


def _b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def _b64url_uint(n: int) -> str:
    return _b64url(n.to_bytes((n.bit_length() + 7) // 8, "big"))


_JWKS = json.dumps({
    "keys": [{
        "kty": "RSA",
        "kid": KEY_ID,
        "use": "sig",
        "alg": "RS256",
        "n": _b64url_uint(_public_numbers.n),
        "e": _b64url_uint(_public_numbers.e),
    }]
}).encode()


def _sign_jwt(role: str) -> str:
    header = {"alg": "RS256", "typ": "JWT", "kid": KEY_ID}
    now = int(time.time())
    payload = {
        "sub": f"dev-{role.lower()}",
        "iss": ISSUER,
        "iat": now,
        "exp": now + TOKEN_TTL_SECONDS,
        "authorities": [f"ROLE_{role}"],
    }
    signing_input = f"{_b64url(json.dumps(header, separators=(',', ':')).encode())}." \
                    f"{_b64url(json.dumps(payload, separators=(',', ':')).encode())}"
    signature = _private_key.sign(
        signing_input.encode(), padding.PKCS1v15(), hashes.SHA256()
    )
    return f"{signing_input}.{_b64url(signature)}"


class Handler(http.server.BaseHTTPRequestHandler):
    def _write(self, status: HTTPStatus, body: dict) -> None:
        payload = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):  # noqa: N802 (BaseHTTPRequestHandler's required name)
        if self.path in ("/.well-known/jwks.json", "/oauth2/jwks"):
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(_JWKS)))
            self.end_headers()
            self.wfile.write(_JWKS)
            return
        self._write(HTTPStatus.NOT_FOUND, {"error": "not found"})

    def do_POST(self):  # noqa: N802
        if self.path != "/dev/login":
            self._write(HTTPStatus.NOT_FOUND, {"error": "not found"})
            return
        length = int(self.headers.get("Content-Length", 0))
        try:
            body = json.loads(self.rfile.read(length) or b"{}")
        except json.JSONDecodeError:
            self._write(HTTPStatus.BAD_REQUEST, {"error": "invalid JSON body"})
            return
        role = body.get("role")
        if role not in ALLOWED_ROLES:
            self._write(HTTPStatus.BAD_REQUEST, {
                "error": f"role must be one of {list(ALLOWED_ROLES)}"
            })
            return
        self._write(HTTPStatus.OK, {"token": _sign_jwt(role)})

    def log_message(self, fmt, *args):  # quiet down default per-request access logs
        pass


if __name__ == "__main__":
    server = http.server.ThreadingHTTPServer(("0.0.0.0", 9000), Handler)
    print("dev-auth-mock listening on :9000 (JWKS + POST /dev/login)")
    server.serve_forever()
