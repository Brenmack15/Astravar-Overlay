#!/usr/bin/env bash
# Exercise the bundled production entry point using the local provider runtime.
set -euo pipefail
repository=$(cd "$(dirname "$0")/.." && pwd)
if [[ -n "${ASTRAVAR_MINIFLARE_PATH:-}" ]]; then
  provider_module=$ASTRAVAR_MINIFLARE_PATH
else
  runtime_dir=$(mktemp -d)
  trap 'rm -rf "$runtime_dir"' EXIT
  npm install --prefix "$runtime_dir" --no-audit --no-fund --no-save miniflare@5.20261123.1-beta
  provider_module=$runtime_dir/node_modules/miniflare
fi
node "$repository/scripts/verify-ascension-runtime.cjs" "$provider_module"
node "$repository/scripts/verify-ascension-provider-recovery.cjs" "$provider_module"
