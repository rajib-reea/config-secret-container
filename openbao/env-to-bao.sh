#!/usr/bin/env bash

set -euo pipefail

# ============================================================
# .env -> OpenBao KV v2 Migration
#
# OpenBao structure:
#
#   cmn/
#   ├── config/
#   │   └── dev
#   │
#   └── secret/
#       └── dev
#
# Resulting paths:
#
#   cmn/config/dev
#   cmn/secret/dev
#
# ============================================================


# ------------------------------------------------------------
# Configuration
# ------------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# This script lives in <repo>/openbao/ while .env sits at the repo root,
# so look one level up. Override with ENV_FILE=... for a different source.
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

ENV_FILE="${ENV_FILE:-$REPO_ROOT/.env}"

BAO_ADDR="${BAO_ADDR:-http://127.0.0.1:8200}"

# Named after the project, not this folder - otherwise the KV mount would
# be called "openbao" rather than the project it holds secrets for.
PROJECT_NAME="${PROJECT_NAME:-$(basename "$REPO_ROOT")}"


# ------------------------------------------------------------
# Read profile from .env
# ------------------------------------------------------------

PROFILE="dev"

if [[ -f "$ENV_FILE" ]]; then

    profile_value="$(
        sed -nE \
            's/^[[:space:]]*SPRING_PROFILES_ACTIVE[[:space:]]*=[[:space:]]*"?([^"]*)"?[[:space:]]*$/\1/p' \
            "$ENV_FILE" \
        | tr -d '\r' \
        | head -n 1
    )"

    if [[ -n "$profile_value" ]]; then
        PROFILE="$profile_value"
    fi

fi

# Remove CR and surrounding whitespace
PROFILE="$(printf '%s' "$PROFILE" | tr -d '\r' | xargs)"


# ------------------------------------------------------------
# OpenBao Secrets Engine
# ------------------------------------------------------------

BAO_MOUNT="$PROJECT_NAME"


# ------------------------------------------------------------
# Logical paths inside the KV engine
# ------------------------------------------------------------

CONFIG_KEY="config/${PROFILE}"
SECRET_KEY="secret/${PROFILE}"


# ------------------------------------------------------------
# Full OpenBao paths
# ------------------------------------------------------------

CONFIG_PATH="${BAO_MOUNT}/${CONFIG_KEY}"
SECRET_PATH="${BAO_MOUNT}/${SECRET_KEY}"


# ------------------------------------------------------------
# OpenBao CLI wrapper
# ------------------------------------------------------------

bao() {

    docker exec \
        -e BAO_ADDR="$BAO_ADDR" \
        -e BAO_TOKEN="$BAO_TOKEN" \
        openbao \
        bao "$@"

}


# ------------------------------------------------------------
# Header
# ------------------------------------------------------------

echo
echo "=========================================="
echo "       .env -> OpenBao Migration"
echo "=========================================="
echo
echo "ENV FILE : $ENV_FILE"
echo "BAO ADDR : $BAO_ADDR"
echo "PROJECT  : $PROJECT_NAME"
echo "PROFILE  : $PROFILE"
echo
echo "ENGINE   : ${BAO_MOUNT}/"
echo "CONFIG   : ${CONFIG_PATH}"
echo "SECRET   : ${SECRET_PATH}"
echo


# ------------------------------------------------------------
# Validate .env
# ------------------------------------------------------------

if [[ ! -f "$ENV_FILE" ]]; then

    echo "ERROR: .env file not found:"
    echo "       $ENV_FILE"
    exit 1

fi


# ------------------------------------------------------------
# Validate BAO_TOKEN
# ------------------------------------------------------------

if [[ -z "${BAO_TOKEN:-}" ]]; then

    echo "ERROR: BAO_TOKEN is not set."
    echo
    echo "Set it with:"
    echo
    echo "  export BAO_TOKEN='YOUR_OPENBAO_TOKEN'"
    echo
    exit 1

fi


# ------------------------------------------------------------
# Determine whether variable is a secret
# ------------------------------------------------------------

is_secret() {

    local name="${1^^}"

    local secret_keywords=(
        PASSWORD
        PASSWD
        PWD
        SECRET
        TOKEN
        PRIVATE_KEY
        ENCRYPTION_KEY
        API_KEY
        ACCESS_KEY
        CLIENT_SECRET
        CREDENTIAL
    )

    local keyword

    for keyword in "${secret_keywords[@]}"; do

        if [[ "$name" == *"$keyword"* ]]; then
            return 0
        fi

    done

    return 1
}


# ------------------------------------------------------------
# Temporary files
# ------------------------------------------------------------

CONFIG_FILE="$(mktemp)"
SECRET_FILE="$(mktemp)"

trap 'rm -f "$CONFIG_FILE" "$SECRET_FILE"' EXIT


# ------------------------------------------------------------
# Counters
# ------------------------------------------------------------

config_count=0
secret_count=0
skipped_count=0


# ------------------------------------------------------------
# Parse .env
# ------------------------------------------------------------

while IFS= read -r line || [[ -n "$line" ]]; do

    # Remove Windows CRLF character
    line="${line%$'\r'}"

    # Trim leading whitespace
    line="${line#"${line%%[![:space:]]*}"}"

    # Skip blank lines
    [[ -z "$line" ]] && continue

    # Skip comments
    [[ "$line" == \#* ]] && continue

    # Skip lines without =
    [[ "$line" != *=* ]] && continue


    # --------------------------------------------------------
    # Extract key and value
    # --------------------------------------------------------

    key="${line%%=*}"
    value="${line#*=}"


    # --------------------------------------------------------
    # Trim whitespace around key
    # --------------------------------------------------------

    key="${key#"${key%%[![:space:]]*}"}"
    key="${key%"${key##*[![:space:]]}"}"


    # --------------------------------------------------------
    # Trim whitespace around value
    # --------------------------------------------------------

    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"


    # --------------------------------------------------------
    # Remove surrounding quotes
    # --------------------------------------------------------

    if [[ "$value" == \"*\" && "$value" == *\" ]]; then

        value="${value:1:${#value}-2}"

    elif [[ "$value" == \'*\' && "$value" == *\' ]]; then

        value="${value:1:${#value}-2}"

    fi


    # --------------------------------------------------------
    # Remove CR
    # --------------------------------------------------------

    value="${value//$'\r'/}"


    # --------------------------------------------------------
    # Skip metadata
    # --------------------------------------------------------

    case "$key" in

        SPRING_PROFILES_ACTIVE)

            echo "SKIP   $key"
            ((skipped_count+=1))
            continue
            ;;

        BAO_TOKEN|BAO_ADDR|PROJECT_NAME)

            echo "SKIP   $key"
            ((skipped_count+=1))
            continue
            ;;

    esac


    # --------------------------------------------------------
    # Skip empty values
    # --------------------------------------------------------

    if [[ -z "$value" ]]; then

        if is_secret "$key"; then

            echo "SKIP   empty secret: $key"

        else

            echo "SKIP   empty config: $key"

        fi

        ((skipped_count+=1))
        continue

    fi


    # --------------------------------------------------------
    # Classify variable
    # --------------------------------------------------------

    if is_secret "$key"; then

        printf '%s=%s\n' "$key" "$value" >> "$SECRET_FILE"

        echo "SECRET $key"

        ((secret_count+=1))

    else

        printf '%s=%s\n' "$key" "$value" >> "$CONFIG_FILE"

        echo "CONFIG $key"

        ((config_count+=1))

    fi

done < "$ENV_FILE"


# ------------------------------------------------------------
# Step 1 - Check OpenBao
# ------------------------------------------------------------

echo
echo "[1/4] Checking OpenBao..."

if ! bao status >/dev/null 2>&1; then

    echo "ERROR: Cannot connect to OpenBao."
    exit 1

fi

echo "      OK"


# ------------------------------------------------------------
# Step 2 - Check token
# ------------------------------------------------------------

echo "[2/4] Checking OpenBao token..."

if ! bao token lookup >/dev/null 2>&1; then

    echo "ERROR: BAO_TOKEN is invalid or does not have permission."
    exit 1

fi

echo "      OK"


# ------------------------------------------------------------
# Step 3 - Check/create project Secrets Engine
# ------------------------------------------------------------

echo "[3/4] Checking Secrets Engine..."

if bao secrets list -format=json 2>/dev/null \
    | grep -q "\"${BAO_MOUNT}/\""; then

    echo "      ${BAO_MOUNT}/ already exists"

else

    echo "      Creating ${BAO_MOUNT}/ ..."

    bao secrets enable \
        -path="$BAO_MOUNT" \
        kv-v2

    echo "      Created ${BAO_MOUNT}/"

fi


# ------------------------------------------------------------
# Step 4 - Migration
# ------------------------------------------------------------

echo "[4/4] Migrating .env..."
echo


# ------------------------------------------------------------
# Build configuration arguments
# ------------------------------------------------------------

CONFIG_ARGS=()

while IFS='=' read -r key value; do

    [[ -z "$key" ]] && continue

    CONFIG_ARGS+=("${key}=${value}")

done < "$CONFIG_FILE"


# ------------------------------------------------------------
# Write configuration
# ------------------------------------------------------------

echo "Writing configuration to:"
echo "  ${CONFIG_PATH}"
echo

if [[ ${#CONFIG_ARGS[@]} -gt 0 ]]; then

    bao kv put \
        "$CONFIG_PATH" \
        "${CONFIG_ARGS[@]}"

else

    echo "No configuration values to migrate."

fi


# ------------------------------------------------------------
# Build secret arguments
# ------------------------------------------------------------

SECRET_ARGS=()

while IFS='=' read -r key value; do

    [[ -z "$key" ]] && continue

    SECRET_ARGS+=("${key}=${value}")

done < "$SECRET_FILE"


# ------------------------------------------------------------
# Write secrets
# ------------------------------------------------------------

echo
echo "Writing secrets to:"
echo "  ${SECRET_PATH}"
echo

if [[ ${#SECRET_ARGS[@]} -gt 0 ]]; then

    bao kv put \
        "$SECRET_PATH" \
        "${SECRET_ARGS[@]}"

else

    echo "No secret values to migrate."

fi


# ------------------------------------------------------------
# Summary
# ------------------------------------------------------------

echo
echo "=========================================="
echo "          Migration completed"
echo "=========================================="
echo
echo "Project        : $PROJECT_NAME"
echo "Profile        : $PROFILE"
echo
echo "Config entries : $config_count"
echo "Secret entries : $secret_count"
echo "Skipped        : $skipped_count"
echo
echo "Secrets Engine : ${BAO_MOUNT}/"
echo
echo "Configuration  : ${CONFIG_PATH}"
echo "Secrets        : ${SECRET_PATH}"
echo


# ------------------------------------------------------------
# Verify configuration
# ------------------------------------------------------------

echo "------------------------------------------"
echo "Configuration"
echo "------------------------------------------"

if bao kv get "$CONFIG_PATH" >/dev/null 2>&1; then

    echo "OK: $CONFIG_PATH"

else

    echo "ERROR: Could not verify $CONFIG_PATH"

fi


# ------------------------------------------------------------
# Verify secrets
# ------------------------------------------------------------

echo
echo "------------------------------------------"
echo "Secrets"
echo "------------------------------------------"

if bao kv get "$SECRET_PATH" >/dev/null 2>&1; then

    echo "OK: $SECRET_PATH"

else

    echo "ERROR: Could not verify $SECRET_PATH"

fi


# ------------------------------------------------------------
# Show logical structure
# ------------------------------------------------------------

echo
echo "------------------------------------------"
echo "Stored paths"
echo "------------------------------------------"

echo
echo "Project:"
echo "  ${BAO_MOUNT}/"

echo
echo "Config:"
echo "  ${CONFIG_PATH}"

echo
echo "Secret:"
echo "  ${SECRET_PATH}"


echo
echo "=========================================="
echo "              Done"
echo "=========================================="
echo
