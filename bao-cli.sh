#!/usr/bin/env bash
set -euo pipefail

: "${BAO_TOKEN:?BAO_TOKEN must be set}"

docker exec \
  -e BAO_ADDR=http://127.0.0.1:8200 \
  -e BAO_TOKEN="$BAO_TOKEN" \
  openbao \
  bao "$@"