#!/usr/bin/env bash
# Fails if any Flyway migration declares a column as bare INT or INTEGER.
# CockroachDB treats both as 64-bit (INT8). Use INT4 for 32-bit columns, which matches the
# Java int fields that Hibernate validates against. INT4, INT8, BIGINT, SMALLINT and INTERVAL
# are not flagged. SQL comments (-- ...) are ignored.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

python3 - "${REPO_ROOT}" <<'PYEOF'
import glob, os, re, sys

root = sys.argv[1]
pattern = re.compile(r"\b(INT|INTEGER)\b", re.IGNORECASE)
violations = []
files = sorted(glob.glob(os.path.join(root, "services", "*", "src", "main", "resources", "db", "migration", "*.sql")))

for path in files:
    with open(path, encoding="utf-8") as fh:
        for number, line in enumerate(fh, start=1):
            code = line.split("--", 1)[0]  # ignore SQL comments
            if pattern.search(code):
                rel = os.path.relpath(path, root)
                violations.append(f"{rel}:{number}: {line.strip()}")

if violations:
    print("Flyway migrations must not use bare INT or INTEGER. Use INT4 instead:")
    print("CockroachDB treats INT and INTEGER as 64-bit, so the column would not match the Java int field.")
    for v in violations:
        print("  " + v)
    sys.exit(1)

print(f"OK: checked {len(files)} migration file(s); no bare INT or INTEGER columns.")
PYEOF
