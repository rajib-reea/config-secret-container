# OpenBao Setup Considerations

This document records the verified solution for running OpenBao with Docker Compose, initializing the server, fixing the Docker volume permission issue, enabling KV v2, and migrating application secrets.

---

## 1. Fresh OpenBao Setup

For a completely fresh local OpenBao instance:

```bash
docker compose down
docker ps -a | grep openbao

docker volume rm openbao-data

docker volume ls | grep openbao

ls -l openbao/config/

docker compose up
```

Check the container:

```bash
docker logs openbao
docker ps -a | grep openbao
```

---

## 2. Check OpenBao Data Permissions

Check the ownership of `/openbao/data`:

```bash
docker exec openbao ls -ldn /openbao/data
```

If the result is similar to:

```text
drwxr-xr-x 2 0 0 4096 ... /openbao/data
```

then the directory is owned by `root:root`.

However, the OpenBao process runs as the `openbao` user.

Verify the process:

```bash
docker exec openbao ps aux
```

---

## 3. Initialization Permission Problem

Attempt initialization:

```bash
docker exec \
  -e BAO_ADDR=http://127.0.0.1:8200 \
  -it openbao \
  bao operator init
```

If the following error occurs:

```text
Error initializing: Error making API request.

URL: PUT http://127.0.0.1:8200/v1/sys/init
Code: 400. Errors:

* failed to initialize barrier:
  failed to persist keyring:
  mkdir /openbao/data/core: permission denied
```

the problem is the ownership of `/openbao/data`.

### Root Cause

```text
/openbao/data
       │
       ├── owner: root
       │
       └── OpenBao process: openbao
                            │
                            └── cannot create /openbao/data/core
```

---

## 4. Fix the Permission

Run the following command as root inside the container:

```bash
docker exec -u 0 openbao \
  chown -R openbao:openbao /openbao/data
```

Verify:

```bash
docker exec openbao ls -ldn /openbao/data
```

Expected result:

```text
drwxr-xr-x 2 100 1000 ... /openbao/data
```

The important change is:

```text
Before:
root:root

After:
openbao:openbao
```

---

## 5. Initialize OpenBao Again

After fixing ownership:

```bash
docker exec \
  -e BAO_ADDR=http://127.0.0.1:8200 \
  -it openbao \
  bao operator init
```

Initialization should now succeed.

OpenBao generates:

* Unseal keys
* Initial root token

For the default configuration used in this setup:

```text
Total Shares: 5
Threshold:    3
```

### Important

Never commit the generated:

* Unseal keys
* Root token

to Git or GitHub.

---

## 6. Unseal OpenBao

Run:

```bash
docker exec \
  -e BAO_ADDR=http://127.0.0.1:8200 \
  -it openbao \
  bao operator unseal
```

Provide a different unseal share each time.

With:

```text
Total Shares: 5
Threshold: 3
```

three different shares are required.

---

## 7. Verify OpenBao Status

Run:

```bash
docker exec \
  -e BAO_ADDR=http://127.0.0.1:8200 \
  -it openbao \
  bao status
```

Expected state:

```text
Key             Value
---             -----
Seal Type       shamir
Initialized     true
Sealed          false
Total Shares    5
Threshold       3
Version         2.6.2
Storage Type    file
HA Enabled      false
```

The important values are:

```text
Initialized     true
Sealed          false
```

---

# Secret Setup

## 8. Configure the OpenBao Address

Set:

```bash
export BAO_ADDR=http://127.0.0.1:8200
```

Set the token locally:

```bash
export BAO_TOKEN='YOUR_OPENBAO_TOKEN'
```

> Never place the real token in this document.

---

## 9. Verify Authentication

Run:

```bash
docker exec \
  -e BAO_ADDR="$BAO_ADDR" \
  -e BAO_TOKEN="$BAO_TOKEN" \
  -it openbao \
  bao token lookup
```

If this succeeds, the token is valid and the CLI can communicate with OpenBao.

---

## 10. Check Secret Engines

Run:

```bash
docker exec \
  -e BAO_ADDR="$BAO_ADDR" \
  -e BAO_TOKEN="$BAO_TOKEN" \
  -it openbao \
  bao secrets list
```

A freshly initialized OpenBao instance should contain system engines similar to:

```text
Path          Type
----          ----
cubbyhole/    cubbyhole
identity/     identity
sys/          system
```

---

# KV v2 Setup

## 11. Enable KV v2

The following command is required **only once**, if `secret/` does not already exist:

```bash
docker exec \
  -e BAO_ADDR="$BAO_ADDR" \
  -e BAO_TOKEN="$BAO_TOKEN" \
  openbao \
  bao secrets enable -path=secret kv-v2
```

Verify:

```bash
docker exec \
  -e BAO_ADDR="$BAO_ADDR" \
  -e BAO_TOKEN="$BAO_TOKEN" \
  -it openbao \
  bao secrets list
```

The result should contain:

```text
secret/
```

---

## 12. List KV Secrets

Run:

```bash
docker exec \
  -e BAO_ADDR="$BAO_ADDR" \
  -e BAO_TOKEN="$BAO_TOKEN" \
  openbao \
  bao kv list secret/
```

The CMN setup can contain:

```text
Keys
----
cmn-auth-svc/
cmn-email-svc/
cmn-subscription-svc/
cmn-workflow-svc/
elasticsearch/
kibana/
minio/
postgres/
```

The logical structure is:

```text
secret/
├── cmn-auth-svc/
├── cmn-email-svc/
├── cmn-subscription-svc/
├── cmn-workflow-svc/
├── elasticsearch/
├── kibana/
├── minio/
└── postgres/
```

---

## 13. Check Token Capabilities

Run:

```bash
docker exec \
  -e BAO_ADDR="$BAO_ADDR" \
  -e BAO_TOKEN="$BAO_TOKEN" \
  openbao \
  bao token capabilities secret/
```

For production, application services should use dedicated OpenBao identities and least-privilege policies instead of the root token.

---

# Service Secret Example

## 14. List a Service

For CMN Auth:

```bash
docker exec \
  -e BAO_ADDR="$BAO_ADDR" \
  -e BAO_TOKEN="$BAO_TOKEN" \
  openbao \
  bao kv list secret/cmn-auth-svc/
```

---

## 15. Read a Service Secret

Example:

```bash
docker exec \
  -e BAO_ADDR="$BAO_ADDR" \
  -e BAO_TOKEN="$BAO_TOKEN" \
  openbao \
  bao kv get secret/cmn-auth-svc/database
```

The stored structure can be:

```text
secret/data/cmn-auth-svc/database

Data
----
username    authuser
password    <stored-secret>
```

Actual passwords must never be placed in this documentation.

---

# Script Setup

## 16. Migration Script

Make the migration script executable:

```bash
chmod +x migrate-secrets.sh
```

If the script was edited on Windows, normalize CRLF line endings:

```bash
sed -i 's/\r$//' migrate-secrets.sh
```

Verify:

```bash
file migrate-secrets.sh
```

Expected:

```text
migrate-secrets.sh: Bourne-Again shell script, ASCII text executable
```

Check the shebang:

```bash
head -n 1 migrate-secrets.sh | cat -A
```

Expected:

```text
#!/usr/bin/env bash$
```

The absence of `^M` confirms that the script does not contain Windows CRLF characters.

---

# OpenBao CLI Wrapper

## 17. Configure `bao-cli.sh`

Make it executable:

```bash
chmod +x bao-cli.sh
```

Normalize line endings:

```bash
sed -i 's/\r$//' bao-cli.sh
```

Verify:

```bash
file bao-cli.sh
head -n 1 bao-cli.sh | cat -A
```

Then use:

```bash
./bao-cli.sh kv list secret/
```

Other examples:

```bash
./bao-cli.sh status
./bao-cli.sh secrets list
./bao-cli.sh kv list secret/
```

---

# Environment Migration

## 18. Set the Project Name

For the CMN platform:

```bash
export PROJECT_NAME=cmn
```

Set the OpenBao token:

```bash
export BAO_TOKEN='YOUR_OPENBAO_TOKEN'
```

Then execute the migration script:

```bash
./env-to-bao.sh
```

If the repository uses the alternative script:

```bash
./env2bao.sh
```

or:

```bash
./migrate-secrets.sh
```

Use the script that exists in the repository.

---

# Final Architecture

The resulting secret structure is:

```text
OpenBao
│
├── cubbyhole/
├── identity/
├── sys/
│
└── secret/                    ← KV v2
    │
    ├── cmn-auth-svc/
    │   └── database
    │
    ├── cmn-email-svc/
    │
    ├── cmn-subscription-svc/
    │
    ├── cmn-workflow-svc/
    │
    ├── elasticsearch/
    │
    ├── kibana/
    │
    ├── minio/
    │
    └── postgres/
```

---

# Complete Fresh Installation

For a fresh local installation:

```bash
# Stop OpenBao
docker compose down

# Remove the local OpenBao data
docker volume rm openbao-data

# Verify
docker volume ls | grep openbao

# Check configuration
ls -l openbao/config/

# Start OpenBao
docker compose up
```

Then:

```bash
# Check container
docker ps -a | grep openbao

# Check data ownership
docker exec openbao ls -ldn /openbao/data

# Fix ownership if root-owned
docker exec -u 0 openbao \
  chown -R openbao:openbao /openbao/data

# Verify
docker exec openbao ls -ldn /openbao/data
```

Initialize:

```bash
docker exec \
  -e BAO_ADDR=http://127.0.0.1:8200 \
  -it openbao \
  bao operator init
```

Unseal three times:

```bash
docker exec \
  -e BAO_ADDR=http://127.0.0.1:8200 \
  -it openbao \
  bao operator unseal
```

Verify:

```bash
docker exec \
  -e BAO_ADDR=http://127.0.0.1:8200 \
  -it openbao \
  bao status
```

Configure:

```bash
export BAO_ADDR=http://127.0.0.1:8200
export BAO_TOKEN='YOUR_OPENBAO_TOKEN'
```

Enable KV v2 if required:

```bash
docker exec \
  -e BAO_ADDR="$BAO_ADDR" \
  -e BAO_TOKEN="$BAO_TOKEN" \
  openbao \
  bao secrets enable -path=secret kv-v2
```

Verify:

```bash
docker exec \
  -e BAO_ADDR="$BAO_ADDR" \
  -e BAO_TOKEN="$BAO_TOKEN" \
  openbao \
  bao kv list secret/
```

Finally:

```bash
export PROJECT_NAME=cmn
./env-to-bao.sh
```

---

# Important Security Considerations

This repository is public, so demonstration files must never contain real credentials.

Use:

```text
.env.example
```

instead of committing real `.env` values.

Recommended `.gitignore`:

```gitignore
.env
.env.*
!.env.example

*.pem
*.key

secrets/
credentials/
```

If a real OpenBao token or password has already been published:

1. Revoke the exposed OpenBao token.
2. Rotate the affected passwords.
3. Rotate API/client credentials.
4. Replace exposed private keys.
5. Remove credentials from Git history where appropriate.
6. Check GitHub secret scanning/security alerts.

---

# Root Cause Summary

The original initialization failure was caused by Docker creating:

```text
/openbao/data
```

with ownership:

```text
root:root
```

while OpenBao runs as:

```text
openbao:openbao
```

Therefore OpenBao could not create:

```text
/openbao/data/core
```

The working fix is:

```bash
docker exec -u 0 openbao \
  chown -R openbao:openbao /openbao/data
```

After that:

```text
/openbao/data
       │
       ▼
openbao:openbao
       │
       ▼
bao operator init
       │
       ▼
OpenBao initialized
       │
       ▼
3 of 5 unseal shares
       │
       ▼
KV v2
       │
       ▼
Application secrets
```

This is the verified local-development solution.

