#!/usr/bin/env bash
# Some GitHub runner Python images omit the recovery encryption dependency.
set -euo pipefail
if python3 -c 'from cryptography.fernet import Fernet' >/dev/null 2>&1; then
  exec python3 -m unittest discover -s scripts/tests -v
fi
astravar_test_env=$(mktemp -d)
trap 'rm -r "$astravar_test_env"' EXIT
python3 -m venv --system-site-packages "$astravar_test_env"
"$astravar_test_env/bin/python" -m pip --disable-pip-version-check install --only-binary=:all: cryptography==50.0.0
"$astravar_test_env/bin/python" -m unittest discover -s scripts/tests -v
