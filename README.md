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

Everything OpenBao lives under `openbao/`. `.env` stays at the root because it
is the project's configuration, not OpenBao's.

```text
config-secret-container/
│
├── .env                        source config, migrated into OpenBao
├── .gitattributes
├── .gitignore
├── README.md
│
└── openbao/
    ├── docker-compose.yaml
    ├── consideration.md
    ├── bao-up.sh
    ├── bao-cli.sh
    ├── bao-token.sh
    ├── env-to-bao.sh
    ├── .openbao-keys.json      created on first run, git-ignored
    └── config/
        └── openbao.hcl
```

| Path                          | Purpose                                            |
| ----------------------------- | -------------------------------------------------- |
| `openbao/bao-up.sh`           | Start, initialise, unseal, optionally migrate      |
| `openbao/bao-token.sh`        | Read the root token and unseal keys                |
| `openbao/bao-cli.sh`          | Run any `bao` command inside the container         |
| `openbao/env-to-bao.sh`       | Migrate `.env` into KV v2                          |
| `openbao/docker-compose.yaml` | Starts OpenBao                                     |
| `openbao/config/openbao.hcl`  | OpenBao server configuration                       |
| `openbao/consideration.md`    | Full setup, troubleshooting and verified procedure |
| `.env`                        | Local/demo environment variables                   |
| `README.md`                   | Project overview and quick start                   |

---

# Detailed Setup

The complete verified installation and troubleshooting procedure is maintained
in [`openbao/consideration.md`](./openbao/consideration.md).

It covers initialisation, sealing and restarts, token retrieval, the Web UI,
the `cmn` secret structure, `.env` migration, CLI usage, Windows/WSL2 notes,
troubleshooting and security considerations.

---

# Quick Start

```bash
cd openbao
./bao-up.sh --migrate
```

That is the whole setup. It starts the container, initialises on first run,
saves the unseal keys and root token to `openbao/.openbao-keys.json`
(git-ignored), unseals, and migrates `.env` into OpenBao. It is safe to re-run.

When it finishes it prints the UI address and the root token:

```text
==> Ready
Initialized     true
Sealed          false

  UI        : http://localhost:8200/ui/
  Method    : Token
  Token     : s.xxxxxxxxxxxxxxxxxxxxxxxx
```

> **Back up `openbao/.openbao-keys.json`.** It holds the only copy of the
> unseal keys and the root token. Without it the data volume cannot be opened.

## Everyday use

```bash
cd openbao

./bao-up.sh            # start and unseal (after a reboot or restart)
./bao-up.sh --status   # show state without changing anything
./bao-token.sh         # print the root token
```

OpenBao re-seals on every container restart. `./bao-up.sh` detects this and
unseals for you — there is no need to initialise again.

## Manual startup

```bash
cd openbao

docker compose up -d
docker compose ps
docker compose logs -f openbao
```

The full manual initialise/unseal sequence is in
[`openbao/consideration.md`](./openbao/consideration.md#9-doing-it-manually).

> There is no `bao secrets enable` step. `env-to-bao.sh` creates the `cmn`
> KV v2 mount itself and skips creation when it already exists.

> There is no ownership fix either. Storage lives at `/openbao/file`, the path
> the image declares as a volume, so it is owned by `openbao` from the start.
> The older `chown -R openbao:openbao /openbao/data` workaround is no longer
> needed.

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

All script invocations below run from the `openbao/` directory:

```bash
cd openbao
export BAO_TOKEN="$(./bao-token.sh)"
```

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
cd openbao

export PROJECT_NAME=cmn
export BAO_TOKEN="$(./bao-token.sh)"
```

Then:

```bash
./env-to-bao.sh
```

The script reads `../.env` by default. Override with `ENV_FILE=...`.

> Always set `PROJECT_NAME` when calling `env-to-bao.sh` directly. Its fallback
> is the repository folder name, which would create a mount called
> `config-secret-container/` instead of `cmn/`. `./bao-up.sh --migrate` passes
> `cmn` for you.

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
sed -i 's/\r$//' openbao/*.sh
```

Make them executable:

```bash
chmod +x openbao/*.sh
```

`.gitattributes` pins `*.sh` to LF, so a fresh clone will not reintroduce CRLF.

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
cd openbao

docker compose down
docker volume rm openbao-data
rm -f .openbao-keys.json

./bao-up.sh --migrate
```

`bao-up.sh` handles initialisation, unsealing, mount creation and migration.
There is no separate permission fix — see
[Repository Structure](#repository-structure) for why.

> Removing `openbao-data` permanently removes the local OpenBao data.

See [`openbao/consideration.md`](./openbao/consideration.md) for the complete reset and recovery procedure.

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
| [`openbao/consideration.md`](./openbao/consideration.md) | Detailed verified setup, troubleshooting and implementation considerations |

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

**[`openbao/consideration.md`](./openbao/consideration.md)**

