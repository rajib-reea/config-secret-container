#!/usr/bin/env bash
set -euo pipefail

: "${BAO_TOKEN:?BAO_TOKEN must be set}"

# -i keeps stdin attached so piped input and heredocs work, e.g.
#   ./bao-cli.sh policy write my-policy -   < policy.hcl
# -t is deliberately omitted: it fails in non-TTY contexts (scripts, CI).
docker exec -i \
  -e BAO_ADDR=http://127.0.0.1:8200 \
  -e BAO_TOKEN="$BAO_TOKEN" \
  openbao \
  bao "$@"