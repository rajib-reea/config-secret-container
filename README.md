# Config Secret Container

Docker-based OpenBao configuration and secret management for the CMN platform.

This repository provides a lightweight OpenBao setup for centrally managing application configuration and secrets across CMN services and environments.

## Overview

The intended OpenBao structure is:

```text
OpenBao
└── cmn/                         ← KV v2 mount
    ├── config/
    │   └── dev                  ← Non-sensitive configuration
    │
    └── secret/
        └── dev                  ← Sensitive configuration
```

The `cmn` mount separates configuration from secrets while keeping both under the same project/environment namespace.

---

## Architecture

```text
                    ┌─────────────────────────┐
                    │      CMN Services       │
                    │                         │
                    │  auth / workflow /      │
                    │  email / subscription   │
                    └────────────┬────────────┘
                                 │
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │        OpenBao          │
                    │                         │
                    │       KV v2: cmn        │
                    └────────────┬────────────┘
                                 │
                    ┌────────────┴────────────┐
                    │                         │
                    ▼                         ▼
             cmn/config/dev            cmn/secret/dev
             Configuration             Secrets
                    │                         │
                    │                         │
                    ▼                         ▼
             Non-sensitive               Sensitive
               values                     values
```

OpenBao is exposed locally at:

```text
http://localhost:8200
```

OpenBao UI:

```text
http://localhost:8200/ui/
```

> The current Docker configuration is intended for local development. TLS is disabled.

---

# Repository Structure

```text
config-secret-container/
│
├── openbao/
│   └── config/
│       └── openbao.hcl
│
├── .env
├── docker-compose.yaml
├── bao-cli.sh
├── env-to-bao.sh
├── consideration.md
└── README.md
```

| File                         | Purpose                                               |
| ---------------------------- | ----------------------------------------------------- |
| `docker-compose.yaml`        | Starts OpenBao                                        |
| `openbao/config/openbao.hcl` | OpenBao server configuration                          |
| `bao-cli.sh`                 | OpenBao CLI convenience wrapper                       |
| `env-to-bao.sh`              | Migrates environment values into OpenBao              |
| `consideration.md`           | Detailed setup, troubleshooting and verified solution |
| `.env`                       | Local/demo environment variables                      |
| `README.md`                  | Project overview and quick-start guide                |

---

# Detailed Setup

The complete verified installation and troubleshooting procedure is maintained in:

## [`consideration.md`](./consideration.md)

It covers:

* Docker Compose startup
* OpenBao initialization
* Unseal
* Status verification
* Docker volume permissions
* `/openbao/data` ownership problems
* KV v2 setup
* `cmn` secret engine structure
* Configuration and secret paths
* `.env` migration
* CLI usage
* Windows/WSL2 CRLF problems
* Troubleshooting
* Security considerations

**For the complete setup procedure, see [`consideration.md`](./consideration.md).**

---

# Quick Start

## 1. Start OpenBao

```bash
docker compose up -d
```

Check the container:

```bash
docker compose ps
```

View logs:

```bash
docker compose logs -f openbao
```

---

# 2. Initialize OpenBao

For a new OpenBao data volume:

```bash
docker exec \
  -e BAO_ADDR=http://127.0.0.1:8200 \
  -it openbao \
  bao operator init
```

Store the generated:

* Unseal keys
* Root token

securely.

**Never commit them to Git.**

---

# 3. Fix Initialization Permission Problems

If initialization fails with:

```text
failed to persist keyring:
mkdir /openbao/data/core: permission denied
```

fix the data directory ownership:

```bash
docker exec -u 0 openbao \
  chown -R openbao:openbao /openbao/data
```

Then run initialization again:

```bash
docker exec \
  -e BAO_ADDR=http://127.0.0.1:8200 \
  -it openbao \
  bao operator init
```

The explanation of this issue is documented in [`consideration.md`](./consideration.md).

---

# 4. Unseal OpenBao

Run:

```bash
docker exec \
  -e BAO_ADDR=http://127.0.0.1:8200 \
  -it openbao \
  bao operator unseal
```

Provide the required number of different unseal keys.

Check status:

```bash
docker exec \
  -e BAO_ADDR=http://127.0.0.1:8200 \
  -it openbao \
  bao status
```

Expected:

```text
Initialized: true
Sealed:      false
```

---

# OpenBao Secret Engine

## 5. Create the `cmn` KV v2 Mount

The CMN platform uses a dedicated KV v2 mount:

```text
cmn/
```

Enable it once:

```bash
export BAO_ADDR=http://127.0.0.1:8200
export BAO_TOKEN='YOUR_OPENBAO_TOKEN'
```

Then:

```bash
docker exec \
  -e BAO_ADDR="$BAO_ADDR" \
  -e BAO_TOKEN="$BAO_TOKEN" \
  openbao \
  bao secrets enable -path=cmn kv-v2
```

Verify:

```bash
docker exec \
  -e BAO_ADDR="$BAO_ADDR" \
  -e BAO_TOKEN="$BAO_TOKEN" \
  openbao \
  bao secrets list
```

The result should contain:

```text
cmn/
```

---

# Configuration and Secret Structure

The `cmn` KV v2 mount contains two logical areas:

```text
cmn/
├── config/
│   └── dev
│
└── secret/
    └── dev
```

## Configuration

Non-sensitive application configuration is stored under:

```text
cmn/config/dev
```

Examples:

```text
SPRING_PROFILES_ACTIVE
server.port
application settings
service URLs
feature flags
non-sensitive integration configuration
```

---

## Secrets

Sensitive values are stored under:

```text
cmn/secret/dev
```

Examples:

```text
database passwords
API keys
client secrets
tokens
private keys
encryption keys
credentials
```

Sensitive values should never be committed to Git.

---

# Environment Separation

The same structure can be extended for other environments:

```text
cmn/
├── config/
│   ├── dev
│   ├── test
│   ├── staging
│   └── prod
│
└── secret/
    ├── dev
    ├── test
    ├── staging
    └── prod
```

This gives a consistent logical model:

```text
<mount>/<type>/<environment>
```

For example:

```text
cmn/config/dev
cmn/secret/dev

cmn/config/test
cmn/secret/test

cmn/config/prod
cmn/secret/prod
```

---

# CLI Examples

## List the `cmn` mount

```bash
./bao-cli.sh kv list cmn/
```

Expected logical structure:

```text
config/
secret/
```

---

## Read Development Configuration

```bash
./bao-cli.sh kv get cmn/config/dev
```

---

## Read Development Secrets

```bash
./bao-cli.sh kv get cmn/secret/dev
```

---

## Write Configuration

Example:

```bash
./bao-cli.sh kv put cmn/config/dev \
  SPRING_PROFILES_ACTIVE=dev \
  SERVICE_NAME=cmn
```

---

## Write Secrets

Example:

```bash
./bao-cli.sh kv put cmn/secret/dev \
  DATABASE_USERNAME=appuser \
  DATABASE_PASSWORD='YOUR_PASSWORD'
```

> Do not put real production credentials into shell history or documentation.

---

# `.env` Migration

The repository provides:

```text
env-to-bao.sh
```

The migration model is:

```text
.env
 │
 ├── non-sensitive values
 │          │
 │          ▼
 │    cmn/config/dev
 │
 └── sensitive values
            │
            ▼
      cmn/secret/dev
```

Set:

```bash
export PROJECT_NAME=cmn
export BAO_TOKEN='YOUR_OPENBAO_TOKEN'
```

Then:

```bash
./env-to-bao.sh
```

The script determines the active Spring profile and separates configuration values from sensitive values.

For example:

```text
.env
 │
 ├── SPRING_PROFILES_ACTIVE=dev
 │
 ├── SERVER_PORT=8080
 │
 ├── DATABASE_USERNAME=appuser
 │
 └── DATABASE_PASSWORD=********
```

becomes conceptually:

```text
cmn/
├── config/
│   └── dev
│       └── SERVER_PORT
│
└── secret/
    └── dev
        ├── DATABASE_USERNAME
        └── DATABASE_PASSWORD
```

The exact classification is handled by the migration script.

---

# Windows / WSL2

If a shell script was edited on Windows, it may contain CRLF line endings.

Normalize the script:

```bash
sed -i 's/\r$//' env-to-bao.sh
```

Make it executable:

```bash
chmod +x env-to-bao.sh
```

Verify:

```bash
file env-to-bao.sh
```

and:

```bash
head -n 1 env-to-bao.sh | cat -A
```

The first line should end with:

```text
$
```

rather than:

```text
^M$
```

---

# OpenBao CLI Wrapper

`bao-cli.sh` allows OpenBao commands to be executed inside the container.

Set:

```bash
export BAO_TOKEN='YOUR_OPENBAO_TOKEN'
```

Then:

```bash
./bao-cli.sh status
```

List engines:

```bash
./bao-cli.sh secrets list
```

List CMN paths:

```bash
./bao-cli.sh kv list cmn/
```

Get configuration:

```bash
./bao-cli.sh kv get cmn/config/dev
```

Get secrets:

```bash
./bao-cli.sh kv get cmn/secret/dev
```

---

# Fresh Reset

To completely reset the local OpenBao instance:

```bash
docker compose down
docker volume rm openbao-data
docker compose up
```

Then repeat:

1. Permission fix, if required
2. Initialization
3. Unseal
4. KV v2 mount creation
5. Configuration/secret migration

> Removing `openbao-data` permanently removes the local OpenBao data.

See [`consideration.md`](./consideration.md) for the complete reset and recovery procedure.

---

# Security

Never commit:

* OpenBao root tokens
* Unseal keys
* Database passwords
* API keys
* Client secrets
* Private keys
* Encryption keys
* Production credentials

For a public repository, use:

```text
.env.example
```

with fake/demo values rather than real `.env` credentials.

Application services should not use the OpenBao root token. Production deployments should use dedicated identities and least-privilege policies.

If credentials have accidentally been committed to a public repository, revoke and rotate them.

---

# Production Considerations

The current setup is intended primarily for local development.

Current characteristics:

```text
Storage:       File
TLS:            Disabled
HA:             Disabled
Deployment:     Docker
```

A production deployment should additionally consider:

* TLS
* Auto-unseal
* KMS/HSM
* High availability
* Backup and recovery
* Audit logging
* Dedicated service identities
* Least-privilege policies
* Secret rotation
* Network restrictions
* Environment isolation

---

# Documentation

| Document                                 | Purpose                                                                    |
| ---------------------------------------- | -------------------------------------------------------------------------- |
| [`README.md`](./README.md)               | Project overview and quick start                                           |
| [`consideration.md`](./consideration.md) | Detailed verified setup, troubleshooting and implementation considerations |

## Recommended Flow

```text
README.md
    │
    │ overview
    ▼
consideration.md
    │
    ├── OpenBao startup
    ├── Permission fix
    ├── Initialization
    ├── Unseal
    ├── KV v2
    ├── cmn/config/dev
    ├── cmn/secret/dev
    ├── Migration
    └── Troubleshooting
```

---

## Summary

The intended CMN OpenBao structure is:

```text
cmn/
├── config/
│   └── dev
│
└── secret/
    └── dev
```

Where:

```text
cmn/config/dev
        │
        └── Non-sensitive configuration

cmn/secret/dev
        │
        └── Sensitive configuration and credentials
```

For the detailed, verified setup and troubleshooting procedure, see:

**[`consideration.md`](./consideration.md)**

