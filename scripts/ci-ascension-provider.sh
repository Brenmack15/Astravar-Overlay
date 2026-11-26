#!/usr/bin/env bash
# Exercise the bundled production entry point using the local provider runtime.
set -euo pipefail
repository=$(cd "$(dirname "$0")/.." && pwd)
if [[ -n "${ASTRAVAR_MINIFLARE_PATH:-}" ]]; then
  exec node "$repository/scripts/verify-ascension-runtime.cjs" "$ASTRAVAR_MINIFLARE_PATH"
fi
runtime_dir=$(mktemp -d)
trap 'rm -rf "$runtime_dir"' EXIT
npm install --prefix "$runtime_dir" --no-audit --no-fund --no-save miniflare@5.20261123.1-beta
node "$repository/scripts/verify-ascension-runtime.cjs" "$runtime_dir/node_modules/miniflare"
