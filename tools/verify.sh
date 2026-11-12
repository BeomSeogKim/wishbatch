#!/usr/bin/env bash
# Run every local check: repository guards, guard tests, and the application build.
# Stage new files first; the repository guard inspects tracked paths only.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

git diff --check
for file in .githooks/* tools/*.sh; do bash -n "$file"; done
python3 tools/check-repository.py
python3 -m unittest discover -s tests -p 'test_*.py'
./gradlew --quiet --console=plain check
echo "verify ok"
