# OpenBao Setup

Local OpenBao on Docker Compose: start it, initialise it, and load `.env` into
KV v2.

Verified against **OpenBao 2.6.2**.

---

> Everything in this document runs from the `openbao/` directory:
>
> ```bash
> cd openbao
> ```

## Quick start

```bash
./bao-up.sh --migrate
```

That is the whole setup. It starts the container, initialises on first run,
saves the unseal keys and root token to `.openbao-keys.json` (git-ignored),
unseals, and migrates `.env` into OpenBao. It is safe to re-run.

When it finishes it prints the UI address and the root token:

```text
==> Ready
Initialized     true
Sealed          false

  UI        : http://localhost:8200/ui/
  Method    : Token
  Token     : s.xxxxxxxxxxxxxxxxxxxxxxxx
```

Everyday use afterwards:

```bash
./bao-up.sh            # start and unseal (after a reboot or restart)
./bao-up.sh --status   # show state without changing anything
./bao-token.sh         # print the root token
```

> **Back up `.openbao-keys.json`.** It holds the only copy of the unseal keys
> and the root token. Without it the data volume cannot be opened, by anyone,
> ever. It is git-ignored so it will not be committed.

---

## Contents

1. [What the setup looks like](#1-what-the-setup-looks-like)
2. [The scripts](#2-the-scripts)
3. [Sealing and restarts](#3-sealing-and-restarts)
4. [Getting the token](#4-getting-the-token)
5. [The Web UI](#5-the-web-ui)
    * [Initialising from the UI](#initialising-from-the-ui-uivaultinit)
6. [What gets migrated](#6-what-gets-migrated)
7. [Reading secrets back](#7-reading-secrets-back)
8. [Starting over](#8-starting-over)
9. [Doing it manually](#9-doing-it-manually)
10. [Storage path and permissions](#10-storage-path-and-permissions)
11. [Running on Windows](#11-running-on-windows)
12. [Troubleshooting](#12-troubleshooting)
13. [Security](#13-security)
14. [Production notes](#14-production-notes)

---

## 1. What the setup looks like

Everything OpenBao lives in one folder; `.env` stays at the repository root
because it is the project's configuration, not OpenBao's:

```text
config-secret-container/
│
├── .env                      source config, migrated into OpenBao
├── .gitignore
├── .gitattributes
├── README.md
│
└── openbao/                  <- everything in this document
    ├── docker-compose.yaml
    ├── consideration.md      this file
    ├── bao-up.sh
    ├── bao-cli.sh
    ├── bao-token.sh
    ├── env-to-bao.sh
    ├── .openbao-keys.json    created on first run, git-ignored
    └── config/
        └── openbao.hcl
```

A single container, one published port, one named volume:

```text
openbao/openbao:2.6
│
├── port      8200               API + UI
├── volume    openbao-data   ->  /openbao/file    (file storage)
└── config    ./config/openbao.hcl  ->  /openbao/config  (read-only)
```

The Compose project name is pinned to `openbao` in `docker-compose.yaml`, so
it does not change with the directory the file sits in. The volume is named
explicitly (`openbao-data`), which is why moving these files did not disturb
the stored secrets.

`env-to-bao.sh` reads `../.env` by default. Override with `ENV_FILE=...` to
migrate a different file.

After migration the secret tree is:

```text
OpenBao
│
├── cubbyhole/                 system
├── identity/                  system
├── sys/                       system
│
└── cmn/                       KV v2, created by env-to-bao.sh
    │
    ├── config/
    │   └── dev                41 non-secret keys
    │
    └── secret/
        └── dev                20 secret keys
```

The mount name comes from `PROJECT_NAME` (default `cmn`). The document name
comes from `SPRING_PROFILES_ACTIVE` in `.env` (default `dev`), so a
`staging` profile would produce `cmn/config/staging` and `cmn/secret/staging`
alongside the `dev` pair.

KV v2 inserts a `data/` segment in the physical API path:

```text
cmn/config/dev   ->   cmn/data/config/dev
cmn/secret/dev   ->   cmn/data/secret/dev
```

---

## 2. The scripts

| Script | Purpose |
| --- | --- |
| `bao-up.sh` | Start, initialise once, unseal, optionally migrate. Idempotent. |
| `bao-token.sh` | Print the root token; `--unseal-keys`, `--format`, `--import`. |
| `bao-approle.sh` | Create a scoped AppRole identity for one profile. |
| `bao-cli.sh` | Run any `bao` command inside the container. |
| `env-to-bao.sh` | Migrate `.env` into KV v2. Called by `bao-up.sh --migrate`. |

### Populating other environments

`env-to-bao.sh` takes the profile from `SPRING_PROFILES_ACTIVE` in `.env`, but
`PROFILE=` overrides it. That is how one `.env` populates several environments:

```bash
export BAO_TOKEN="$(./bao-token.sh)"
export PROJECT_NAME=cmn

PROFILE=staging ./env-to-bao.sh     # -> cmn/config/staging, cmn/secret/staging
PROFILE=prod    ./env-to-bao.sh     # -> cmn/config/prod,    cmn/secret/prod
```

In a real deployment each environment would of course carry its own values;
this simply gets the paths populated.

### Scoped identities

`bao-approle.sh <profile>` gives a service its own credentials instead of the
root token:

```bash
./bao-approle.sh prod
set -a && . ./.openbao-approle-prod.env && set +a
```

It creates a read-only policy limited to `cmn/config/<profile>` and
`cmn/secret/<profile>`, enables the approle auth method if needed, issues
`BAO_ROLE_ID`/`BAO_SECRET_ID` into a git-ignored file, then verifies both that
the credentials **can** read their own environment and **cannot** read another.

> The policy also grants read on the profile-less paths `cmn/data/config` and
> `cmn/data/secret`. Spring Cloud Vault probes those before the profile-specific
> ones; with permission they return 404 and are ignored, but **without**
> permission they return 403, which `fail-fast: true` turns into a startup
> failure:
>
> ```text
> VaultException: Status 403 Forbidden [cmn/data/secret]: permission denied
> ```
>
> Granting read on a path that does not exist discloses nothing.

`bao-up.sh` options:

```bash
./bao-up.sh              # start + initialise (first run) + unseal
./bao-up.sh --migrate    # the above, then migrate .env
./bao-up.sh --status     # report state and exit
```

Using `bao-cli.sh` directly:

```bash
export BAO_TOKEN="$(./bao-token.sh)"

./bao-cli.sh status
./bao-cli.sh secrets list
./bao-cli.sh kv list cmn/
./bao-cli.sh kv get cmn/config/dev
```

The wrapper runs `docker exec -i`. The `-i` keeps stdin attached so piped
input and heredocs work:

```bash
./bao-cli.sh policy write cmn-read - < policy.hcl
```

Without it, `docker exec` discards stdin and the command fails with the
misleading `'policy' parameter not supplied or empty`. `-t` is deliberately
omitted — it breaks in non-TTY contexts with `the input device is not a TTY`.

---

## 3. Sealing and restarts

OpenBao uses a Shamir seal with no auto-unseal, so **it comes back sealed after
every restart**: `docker restart`, `docker compose up`, a Docker Desktop
restart, or a reboot.

The data is intact, just locked. Unsealing needs 3 of the 5 shares — which is
what `bao-up.sh` does for you:

```bash
./bao-up.sh
```

```text
==> Already initialised
==> Unsealing
==> Ready
```

Do **not** run `bao operator init` again after a restart. The instance is
already initialised; re-initialising is neither possible nor necessary.

The healthcheck is configured to stay `healthy` while sealed:

```text
/v1/sys/health?standbyok=true&sealedcode=200&uninitcode=200
```

Without those parameters the endpoint returns `501` when uninitialised and
`503` when sealed, and the container reports `unhealthy` after every restart
until someone unseals it by hand.

---

## 4. Getting the token

The root token is printed **once**, by `bao operator init`. It cannot be
recovered from a running server. `bao-up.sh` captures it for you:

```bash
./bao-token.sh                 # root token
./bao-token.sh --unseal-keys   # the 5 shares, one per line
./bao-token.sh --format        # which file shape was detected
./bao-token.sh --import FILE   # adopt a Web UI download

export BAO_TOKEN="$(./bao-token.sh)"
```

These read `.openbao-keys.json`, which `bao-up.sh` writes on first run. If the
instance was initialised through the Web UI instead, import that download
first — see [Initialising from the UI](#initialising-from-the-ui-uivaultinit).

### If `.openbao-keys.json` is lost

The token is unrecoverable. With a quorum of 3 unseal keys you can mint a new
root token:

```bash
./bao-cli.sh operator generate-root -init          # returns a nonce and an OTP
./bao-cli.sh operator generate-root -nonce=<nonce> # x3, one share each
./bao-cli.sh operator unwrap -otp=<otp> <encoded-token>
```

Without the token **and** without 3 shares, the volume is permanently
unreadable. The only way forward is [starting over](#8-starting-over).

### Non-root tokens

Avoid the root token for routine work:

```bash
./bao-cli.sh policy write cmn-read - <<'EOF'
path "cmn/data/*"     { capabilities = ["read", "list"] }
path "cmn/metadata/*" { capabilities = ["read", "list"] }
EOF

./bao-cli.sh token create -policy=cmn-read -ttl=24h
./bao-cli.sh token revoke <token>
```

---

## 5. The Web UI

```text
http://localhost:8200/ui/
```

`http://localhost:8200/` returns a `307` redirect to `/ui/`.

1. **Method:** `Token`
2. **Token:** `./bao-token.sh`
3. Leave **Namespace** empty — namespaces are an enterprise feature and a value
   here causes `permission denied`.

Data lives under **Secrets → `cmn/`**.

> **8200 is the only port this stack publishes.** If a browser shows nothing on
> some other port, check what actually owns it:
>
> ```bash
> docker ps --format '{{.Names}}\t{{.Ports}}'
> ```

### Initialising from the UI (`/ui/vault/init`)

```text
http://localhost:8200/ui/vault/init
```

This is the browser equivalent of `bao operator init`. `./bao-up.sh` already
does this for you, so the page is only needed if you prefer a GUI or are
setting up an instance this repo's scripts do not manage.

**On a fresh, uninitialised instance** the page asks for the number of key
shares and the threshold (use **5** and **3** to match the rest of this
document), then shows the generated unseal keys and root token **once** and
offers a JSON download. Take the download — there is no second chance.

**On an instance that is already initialised** the page is a dead end. The UI
checks `GET /v1/sys/init`:

```json
{"initialized": true}
```

and routes you to the unseal or sign-in screen instead. Submitting anyway
returns:

```json
{"errors":["Vault is already initialized"]}
```

That error is not a fault to fix. It means the instance is working and you
should be signing in, not initialising. Get the token with `./bao-token.sh`.

#### Adopting the UI's download

The UI downloads the raw `sys/init` response, which uses **different field
names** from the CLI's `-format=json` output:

| Source | Unseal keys field | Token field |
| --- | --- | --- |
| `bao operator init -format=json` | `unseal_keys_b64`, `unseal_keys_hex` | `root_token` |
| Web UI `/ui/vault/init` | `keys_base64`, `keys` | `root_token` |

Both hold the same secrets. `bao-token.sh` understands either shape — check
which one you have with:

```bash
./bao-token.sh --format
```

To make a UI download the file the scripts use:

```bash
./bao-token.sh --import ~/Downloads/openbao-keys.json
```

That copies it to `.openbao-keys.json` (backing up any existing file first),
after which `./bao-up.sh` can unseal normally and `./bao-token.sh` prints the
token. Without importing, `bao-up.sh` stops with:

```text
ERROR: OpenBao is initialised but .openbao-keys.json is missing.
```

---

## 6. What gets migrated

`env-to-bao.sh` splits `.env` into two documents. A variable is a **secret** if
its name contains any of:

```text
PASSWORD   PASSWD   PWD              SECRET
TOKEN      API_KEY  ACCESS_KEY       CREDENTIAL
PRIVATE_KEY         ENCRYPTION_KEY   CLIENT_SECRET
```

Everything else is config. Skipped: empty values, and
`SPRING_PROFILES_ACTIVE`, `BAO_TOKEN`, `BAO_ADDR`, `PROJECT_NAME`.

Expected result for the bundled `.env`:

```text
Config entries : 41
Secret entries : 20
Skipped        : 17
```

---

## 7. Reading secrets back

```bash
export BAO_TOKEN="$(./bao-token.sh)"

./bao-cli.sh kv list cmn/                                # config/ secret/
./bao-cli.sh kv get cmn/config/dev                       # whole document
./bao-cli.sh kv get -field=POSTGRES_USER cmn/config/dev  # single value
./bao-cli.sh kv get -format=json cmn/secret/dev          # JSON
./bao-cli.sh token capabilities cmn/                     # what this token may do
```

---

## 8. Starting over

This destroys every secret in the volume:

```bash
cd openbao

docker compose down
docker volume rm openbao-data
rm -f .openbao-keys.json

./bao-up.sh --migrate
```

The old `.openbao-keys.json` belongs to the destroyed volume and is useless. If
you leave it in place, `bao-up.sh` backs it up to a timestamped `.bak` before
writing the new one.

---

## 9. Doing it manually

`bao-up.sh` exists so you do not have to, but the underlying sequence is:

```bash
cd openbao

# Start
docker compose up -d

# Initialise - prints 5 unseal keys and the root token, ONCE
docker exec -i -e BAO_ADDR=http://127.0.0.1:8200 \
  openbao bao operator init -format=json > .openbao-keys.json

# Unseal with 3 different shares
docker exec -i -e BAO_ADDR=http://127.0.0.1:8200 \
  openbao bao operator unseal "<share-1>"
docker exec -i -e BAO_ADDR=http://127.0.0.1:8200 \
  openbao bao operator unseal "<share-2>"
docker exec -i -e BAO_ADDR=http://127.0.0.1:8200 \
  openbao bao operator unseal "<share-3>"

# Verify
docker exec -i -e BAO_ADDR=http://127.0.0.1:8200 openbao bao status
# Initialized true / Sealed false

# Migrate
export BAO_TOKEN="$(./bao-token.sh)"
export PROJECT_NAME=cmn
./env-to-bao.sh
```

There is no `bao secrets enable` step: `env-to-bao.sh` creates the
`$PROJECT_NAME` KV v2 mount itself, and skips creation when it already exists.
Manually enabling a `secret/` mount produces an orphan that nothing uses.

> **Set `PROJECT_NAME` when calling `env-to-bao.sh` directly.** `bao-up.sh`
> passes `cmn`, but the script's own fallback is the repository folder name,
> which would create a mount called `config-secret-container/` instead.

---

## 10. Storage path and permissions

Storage is at **`/openbao/file`**, not `/openbao/data`. This matters.

`/openbao/file` is declared as a `VOLUME` in the image and ships owned by
`openbao:openbao` (uid 100). A named volume mounted onto an existing image
directory **inherits that directory's ownership**:

```bash
$ docker run --rm -v test:/openbao/file openbao/openbao:2.6 ls -ldn /openbao/file
drwxr-xr-x 2 100 1000 ... /openbao/file      # openbao:openbao
```

`/openbao/data` does not exist in the image, so Docker creates it fresh as
`root:root`:

```bash
$ docker run --rm -v test:/openbao/data openbao/openbao:2.6 ls -ldn /openbao/data
drwxr-xr-x 2 0 0 ... /openbao/data           # root:root
```

OpenBao runs as uid 100 and cannot write there, so initialisation fails with:

```text
* failed to initialize barrier: failed to persist keyring:
  mkdir /openbao/data/core: permission denied
```

The historical workaround was a manual chown after every volume creation:

```bash
docker exec -u 0 openbao chown -R openbao:openbao /openbao/data
```

Using the image's own path removes the problem instead of patching it. **If you
change the storage path, keep it under a directory the image already declares.**

`SKIP_CHOWN=true` is set in `docker-compose.yaml` because the entrypoint's
chown pass now has nothing to do — the volume already has correct ownership and
the config mount is read-only. It only produced noise:

```text
chown: /openbao/config: Read-only file system
Could not chown /openbao/config (may not have appropriate permissions)
```

---

## 11. Running on Windows

### Use WSL2 or Git Bash, not the `docker-desktop` distribution

Bare `wsl` may drop you into the internal `docker-desktop` distribution, which
refuses to run the Docker CLI:

```text
It looks like you have tried to invoke the docker CLI from the docker-desktop
WSL2 distribution. This is not supported.
```

Install a real distribution and make it the default:

```powershell
wsl --install -d Ubuntu-24.04
wsl --set-default Ubuntu-24.04
```

Then enable **Docker Desktop → Settings → Resources → WSL Integration** for it.

### Git Bash path conversion

Git Bash rewrites absolute paths in arguments, so container paths break:

```bash
$ docker exec openbao ls -ldn /openbao/file
ls: C:/Program Files/Git/openbao/file: No such file or directory
```

Set `MSYS_NO_PATHCONV=1` (the repo's scripts already do this internally):

```bash
export MSYS_NO_PATHCONV=1
```

### PowerShell has no `grep`

```powershell
docker ps -a --filter name=openbao      # preferred
docker ps -a | Select-String openbao    # literal grep equivalent
```

### Line endings

`*.sh` must be LF. A CRLF shebang fails inside the container with:

```text
bash: ./bao-cli.sh: /usr/bin/env^M: bad interpreter
```

`.gitattributes` enforces LF on fresh clones. To repair existing files:

```bash
sed -i 's/\r$//' *.sh
```

### `jq` is not bundled with Git Bash

The scripts avoid `jq` for this reason. To install it anyway:
`winget install jqlang.jq`, or `apt install jq` inside WSL.

---

## 12. Troubleshooting

### `failed to read .env: unexpected character "`" in variable name`

The `.env` file contains Markdown code fences (```` ```dotenv ````). Compose
parses `.env` before reading `docker-compose.yaml`, so this fails before
anything starts. Delete the fence lines.

### UI: `Authentication failed: permission denied`

Check the token server-side first:

```bash
curl -s -o /dev/null -w "%{http_code}\n" \
  -H "X-Vault-Token: $(./bao-token.sh)" \
  http://localhost:8200/v1/auth/token/lookup-self
```

`200` means the token is fine and the fault is in the browser:

| Cause | Fix |
| --- | --- |
| Stale token cached from a destroyed instance | Clear site data for `localhost:8200`, hard-reload, or use a private window |
| Token copied with a trailing space, quotes or backticks | Re-copy the value only |
| Something typed in **Namespace** | Clear the field |
| OpenBao is sealed | `./bao-up.sh` |

`403` means the token really is dead — the instance was re-initialised. Get the
current one with `./bao-token.sh`.

### `Vault is already initialized`

You opened `/ui/vault/init` (or ran `bao operator init`) against an instance
that is already set up. Nothing is broken — sign in instead:

```bash
./bao-token.sh
```

Initialisation happens exactly once per volume. To genuinely start fresh, see
[§8](#8-starting-over).

### `OpenBao is initialised but .openbao-keys.json is missing`

The instance was initialised by something other than `bao-up.sh` — usually the
Web UI. Import the file it downloaded:

```bash
./bao-token.sh --import ~/Downloads/openbao-keys.json
```

If that download is gone and no unseal keys survive anywhere, the volume cannot
be opened. See [§8](#8-starting-over).

### `mkdir /openbao/.../core: permission denied`

The storage path is not owned by uid 100. See
[§10](#10-storage-path-and-permissions).

### Container reports `unhealthy`

Usually it is simply sealed. Run `./bao-up.sh`. If the healthcheck in
`docker-compose.yaml` lacks `sealedcode=200&uninitcode=200`, a sealed instance
always reports unhealthy.

### `api_addr` must be routable

```hcl
api_addr = "http://0.0.0.0:8200"    # WRONG - a bind address, not reachable
api_addr = "http://127.0.0.1:8200"  # correct
```

`listener.address` is where the server *binds* — `0.0.0.0:8200` is correct
there. `api_addr` is what the server advertises to clients and uses for
redirects.

### `'policy' parameter not supplied or empty`

`docker exec` without `-i` discards stdin, so heredocs and pipes silently
deliver nothing. `bao-cli.sh` passes `-i`.

### `WARNING: ignoring duplicate configuration found in directory`

The entrypoint already passes `-config=/openbao/config`. Passing the file again
in `command:` loads it twice. The compose file now passes only `server`.

---

## 13. Security

This repository is public. No file may contain real credentials.

**Never commit:** unseal keys, root tokens, real passwords, API keys, private
keys.

`.gitignore` covers `.openbao-keys.json`, `bao-init.json`, `*unseal*`,
`*root-token*`, `*.pem`, `*.key`, `secrets/`, `credentials/`.

The committed `.env` holds deliberately fake demo values and says so in its
header. Real local credentials belong in an untracked copy.

> `.env` is currently **tracked** by Git, so listing it in `.gitignore` does not
> untrack it. To make it local-only:
>
> ```bash
> git mv .env .env.example
> cp .env.example .env        # local working copy, now ignored
> ```

### If a credential has been published

1. Revoke the exposed token — `./bao-cli.sh token revoke <token>`
2. Rotate affected passwords
3. Rotate API and client credentials
4. Replace exposed private keys
5. Purge from Git history where appropriate
6. Check GitHub secret scanning alerts

---

## 14. Production notes

This setup is for local development. For production:

* **Enable TLS.** `tls_disable = true` sends the root token in clear text.
* **Replace the `file` backend.** OpenBao 2.6 warns it is deprecated and must
  be migrated before v2.7.0. Use Raft.
* **Use auto-unseal** (transit or a cloud KMS) instead of manual Shamir shares.
* **Never use the root token from an application.** Give each service its own
  identity — AppRole, Kubernetes auth, or JWT — with a least-privilege policy.
* **Distribute the unseal shares** among different people. Five shares held by
  one person is a single point of failure, not a quorum.
* **Do not keep `.openbao-keys.json` on disk.** It defeats the purpose of
  splitting the key.
