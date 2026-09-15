#!/usr/bin/env bash

set -euo pipefail

# ============================================================
# Bring OpenBao up, ready to use, in one command.
#
#   ./bao-up.sh              start + initialise (first run) + unseal
#   ./bao-up.sh --migrate    the above, then migrate .env into OpenBao
#   ./bao-up.sh --status     show current state and exit
#
# Safe to run repeatedly. It initialises only once and unseals only
# when sealed.
#
# Unseal keys and the root token are written to .openbao-keys.json,
# which is git-ignored. Losing that file makes the volume unrecoverable.
# ============================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

KEYS_FILE="${KEYS_FILE:-$SCRIPT_DIR/.openbao-keys.json}"
CONTAINER="${CONTAINER:-openbao}"
BAO_ADDR="${BAO_ADDR:-http://127.0.0.1:8200}"
HOST_ADDR="${HOST_ADDR:-http://localhost:8200}"
PROJECT_NAME="${PROJECT_NAME:-cmn}"

# Git Bash on Windows rewrites absolute paths in arguments; this stops it
# mangling container paths such as /openbao/file.
export MSYS_NO_PATHCONV=1

MIGRATE=0
STATUS_ONLY=0

for arg in "$@"; do
    case "$arg" in
        --migrate) MIGRATE=1 ;;
        --status)  STATUS_ONLY=1 ;;
        -h|--help)
            sed -n '5,18p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "Unknown option: $arg" >&2
            echo "Usage: $0 [--migrate] [--status]" >&2
            exit 2
            ;;
    esac
done


# ------------------------------------------------------------
# Small helpers
# ------------------------------------------------------------

say()  { printf '%s\n' "$*"; }
step() { printf '\n==> %s\n' "$*"; }
warn() { printf 'WARNING: %s\n' "$*" >&2; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# Run the bao CLI inside the container.
bao_exec() {
    docker exec -i -e BAO_ADDR="$BAO_ADDR" "$CONTAINER" bao "$@"
}

# Run the bao CLI inside the container, authenticated.
bao_auth() {
    docker exec -i -e BAO_ADDR="$BAO_ADDR" -e BAO_TOKEN="$(root_token)" \
        "$CONTAINER" bao "$@"
}

# Token and unseal keys are read by bao-token.sh, which understands both the
# CLI format (unseal_keys_b64) and the Web UI download format (keys_base64).
# Keeping the parsing in one place means both entry points stay in sync.

# Print the N-th (0-based) unseal key.
unseal_key() {
    KEYS_FILE="$KEYS_FILE" ./bao-token.sh --unseal-keys | sed -n "$(($1 + 1))p"
}

root_token() {
    [[ -f "$KEYS_FILE" ]] || die "Keys file not found: $KEYS_FILE"
    KEYS_FILE="$KEYS_FILE" ./bao-token.sh
}

# seal-status field via the HTTP API (no auth required).
seal_status() {
    docker exec -i "$CONTAINER" \
        wget -q -O - "$BAO_ADDR/v1/sys/seal-status" 2>/dev/null || true
}

is_initialized() {
    seal_status | grep -q '"initialized":[[:space:]]*true'
}

is_sealed() {
    seal_status | grep -q '"sealed":[[:space:]]*true'
}


# ------------------------------------------------------------
# Preconditions
# ------------------------------------------------------------

command -v docker >/dev/null 2>&1 || die "docker not found in PATH."

docker info >/dev/null 2>&1 \
    || die "Cannot talk to the Docker daemon. Is Docker Desktop running?"


# ------------------------------------------------------------
# Status-only mode
# ------------------------------------------------------------

if [[ "$STATUS_ONLY" -eq 1 ]]; then

    if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
        say "Container '$CONTAINER' is not running."
        exit 1
    fi

    bao_exec status || true

    if [[ -f "$KEYS_FILE" ]]; then
        say
        say "Keys file : $KEYS_FILE"
        say "UI        : $HOST_ADDR/ui/"
    fi

    exit 0
fi


# ------------------------------------------------------------
# 1. Start the stack
# ------------------------------------------------------------

step "Starting OpenBao"

docker compose up -d

# ------------------------------------------------------------
# 2. Wait for the API to answer
# ------------------------------------------------------------

step "Waiting for the API"

for _ in $(seq 1 60); do
    if [[ -n "$(seal_status)" ]]; then
        say "    API is responding"
        break
    fi
    sleep 1
done

[[ -n "$(seal_status)" ]] \
    || die "OpenBao did not respond within 60s. Check: docker logs $CONTAINER"


# ------------------------------------------------------------
# 3. Initialise once
# ------------------------------------------------------------

if is_initialized; then

    step "Already initialised"

    [[ -f "$KEYS_FILE" ]] || die \
"OpenBao is initialised but $KEYS_FILE is missing.

       If you initialised through the Web UI (/ui/vault/init), adopt the
       file it downloaded:

           ./bao-token.sh --import ~/Downloads/openbao-keys.json

       Otherwise, without the unseal keys this volume cannot be opened.
       If the data is disposable, start over with:

           docker compose down && docker volume rm openbao-data && ./bao-up.sh"

else

    step "Initialising (first run)"

    if [[ -f "$KEYS_FILE" ]]; then
        backup="$KEYS_FILE.$(date +%Y%m%d%H%M%S).bak"
        warn "Existing $KEYS_FILE belongs to an older instance - backing it up to $backup"
        mv "$KEYS_FILE" "$backup"
    fi

    umask 077
    bao_exec operator init -format=json > "$KEYS_FILE"

    say "    Wrote unseal keys and root token to $KEYS_FILE"
    say "    This file is git-ignored. Back it up - it cannot be regenerated."
fi


# ------------------------------------------------------------
# 4. Unseal when sealed
# ------------------------------------------------------------

if is_sealed; then

    step "Unsealing"

    for i in 0 1 2; do
        key="$(unseal_key "$i")"
        [[ -n "$key" ]] || die "Could not read unseal key $((i + 1)) from $KEYS_FILE"
        bao_exec operator unseal "$key" >/dev/null
    done

    is_sealed && die "Still sealed after 3 shares. Inspect: $KEYS_FILE"

    say "    Unsealed"

else
    step "Already unsealed"
fi


# ------------------------------------------------------------
# 5. Optional migration
# ------------------------------------------------------------

if [[ "$MIGRATE" -eq 1 ]]; then

    step "Migrating .env into OpenBao"

    BAO_TOKEN="$(root_token)" PROJECT_NAME="$PROJECT_NAME" \
        ./env-to-bao.sh
fi


# ------------------------------------------------------------
# 6. Report
# ------------------------------------------------------------

step "Ready"

bao_exec status | grep -E 'Initialized|Sealed|Version|Storage Type' || true

cat <<EOF

  UI        : $HOST_ADDR/ui/
  Method    : Token
  Token     : $(root_token)

  Keys file : $KEYS_FILE

  Shell use :
      export BAO_ADDR=$BAO_ADDR
      export BAO_TOKEN="\$(./bao-token.sh)"
      ./bao-cli.sh kv list $PROJECT_NAME/

EOF
