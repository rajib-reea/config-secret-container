#!/usr/bin/env bash

set -euo pipefail

# ============================================================
# Read the root token / unseal keys out of the saved keys file.
#
#   ./bao-token.sh                     root token
#   ./bao-token.sh --unseal-keys       the 5 shares, one per line
#   ./bao-token.sh --format            which file format was detected
#   ./bao-token.sh --import <file>     adopt a keys file downloaded from
#                                      the Web UI (/ui/vault/init)
#
# Typical use:
#   export BAO_TOKEN="$(./bao-token.sh)"
#
# ------------------------------------------------------------
# Two file shapes are understood, because OpenBao emits different
# JSON depending on how it was initialised:
#
#   CLI  - bao operator init -format=json
#          { "unseal_keys_b64": [...], "unseal_keys_hex": [...],
#            "root_token": "..." }
#
#   UI   - http://localhost:8200/ui/vault/init  (raw sys/init response)
#          { "keys": [...], "keys_base64": [...], "root_token": "..." }
#
# Both carry the same secrets under different field names.
# ============================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KEYS_FILE="${KEYS_FILE:-$SCRIPT_DIR/.openbao-keys.json}"


# ------------------------------------------------------------
# --import: adopt a UI download as the canonical keys file
# ------------------------------------------------------------

if [[ "${1:-}" == "--import" ]]; then

    src="${2:-}"

    [[ -n "$src" ]] || { echo "Usage: $0 --import <downloaded-file.json>" >&2; exit 2; }
    [[ -f "$src" ]] || { echo "ERROR: no such file: $src" >&2; exit 1; }

    grep -q '"root_token"' "$src" \
        || { echo "ERROR: $src has no root_token - is it an OpenBao keys file?" >&2; exit 1; }

    grep -qE '"(unseal_keys_b64|keys_base64|keys)"' "$src" \
        || { echo "ERROR: $src has no unseal keys." >&2; exit 1; }

    if [[ -f "$KEYS_FILE" ]]; then
        backup="$KEYS_FILE.$(date +%Y%m%d%H%M%S).bak"
        echo "Backing up existing keys file to $backup" >&2
        mv "$KEYS_FILE" "$backup"
    fi

    umask 077
    cp "$src" "$KEYS_FILE"

    echo "Imported $src -> $KEYS_FILE" >&2
    exit 0
fi


# ------------------------------------------------------------
# Everything else needs the keys file to exist
# ------------------------------------------------------------

if [[ ! -f "$KEYS_FILE" ]]; then
    cat >&2 <<EOF
ERROR: $KEYS_FILE not found.

The root token is printed only once, when OpenBao is initialised, and cannot
be recovered from a running server.

  * Not initialised yet?          ./bao-up.sh
  * Initialised via the Web UI?   ./bao-token.sh --import <downloaded-file>
EOF
    exit 1
fi


# ------------------------------------------------------------
# Detect which shape the file uses
# ------------------------------------------------------------

detect_format() {
    if grep -q '"unseal_keys_b64"' "$KEYS_FILE"; then
        echo cli
    elif grep -q '"keys_base64"' "$KEYS_FILE"; then
        echo ui
    elif grep -q '"keys"' "$KEYS_FILE"; then
        echo ui-hex
    else
        echo unknown
    fi
}

FORMAT="$(detect_format)"

if [[ "${1:-}" == "--format" ]]; then
    case "$FORMAT" in
        cli)     echo "cli    (bao operator init -format=json)" ;;
        ui)      echo "ui     (Web UI /ui/vault/init download)" ;;
        ui-hex)  echo "ui-hex (Web UI download, hex keys only)" ;;
        *)       echo "unknown"; exit 1 ;;
    esac
    exit 0
fi

[[ "$FORMAT" != "unknown" ]] \
    || { echo "ERROR: unrecognised keys file format: $KEYS_FILE" >&2; exit 1; }


# ------------------------------------------------------------
# Unseal keys
# ------------------------------------------------------------

if [[ "${1:-}" == "--unseal-keys" ]]; then

    case "$FORMAT" in
        cli)    array='unseal_keys_b64' ;;
        ui)     array='keys_base64' ;;
        ui-hex) array='keys' ;;
    esac

    # Isolate just this array's [...] and print one entry per line.
    #
    # Newlines are stripped first so the same expression works for both
    # pretty-printed JSON (the CLI writes it multi-line) and compact JSON
    # (the Web UI downloads it on a single line). A sed line-range would
    # match both arrays at once on a single-line file.
    #
    # bao operator unseal accepts either base64 or hex shares.
    tr -d '\n\r' < "$KEYS_FILE" \
        | grep -oE "\"${array}\"[[:space:]]*:[[:space:]]*\[[^]]*\]" \
        | grep -oE '"[A-Za-z0-9+/=]{20,}"' \
        | tr -d '"'

    exit 0
fi


# ------------------------------------------------------------
# Root token (default)
# ------------------------------------------------------------

token="$(
    grep -oE '"root_token":[[:space:]]*"[^"]+"' "$KEYS_FILE" \
        | head -n 1 \
        | sed -E 's/.*"root_token":[[:space:]]*"//; s/"$//'
)"

[[ -n "$token" ]] || { echo "ERROR: no root_token in $KEYS_FILE" >&2; exit 1; }

printf '%s\n' "$token"
