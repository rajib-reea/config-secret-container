# cmn-service

Reactive Spring Boot service whose configuration and secrets come from a
**HashiCorp Vault-compatible** secret store.

* **Spring Boot 4.1.1** on **JDK 25**, **WebFlux** (Netty, functional routing)
* **Hexagonal architecture** — the domain and application layers contain no Spring
* Configuration from Vault via Spring Cloud Vault, resolved per profile

## OpenBao or HashiCorp Vault

The local stack in [`../openbao`](../openbao) runs **OpenBao**, which implements
the Vault API. Outside `local`, this service does not care which one it talks to
and is configured entirely with **standard Vault conventions**:

```text
VAULT_ADDR   VAULT_TOKEN   VAULT_NAMESPACE   VAULT_ROLE_ID   VAULT_SECRET_ID
```

Point `VAULT_ADDR` at a real Vault cluster and nothing else changes. The `BAO_*`
names are accepted as fallbacks so the scripts in `../openbao` keep working.

Exactly one thing genuinely differs between the two — the health indicator. See
[Vault vs OpenBao](#vault-vs-openbao) below.

---

## Quick start

```bash
# 1. The store, populated
cd ../openbao && ./bao-up.sh --migrate

# 2. The service
cd ../cmn-service
VAULT_TOKEN="$(cd ../openbao && ./bao-token.sh)" \
  mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Or the whole stack in Docker:

```bash
cd ../openbao
VAULT_TOKEN="$(./bao-token.sh)" docker compose --profile app up -d --build
```

Then:

```bash
curl localhost:8080/api/v1/environment
curl localhost:8080/api/v1/configuration
```

---

## Architecture

```text
                    HTTP
                      │
        ┌─────────────▼──────────────┐
        │  infrastructure/adapter/in │   ConfigurationHandler
        │           /web             │   WebRoutingConfiguration
        └─────────────┬──────────────┘
                      │  calls
        ┌─────────────▼──────────────┐
        │   domain/port/in           │   InspectConfigurationUseCase
        └─────────────┬──────────────┘
                      │  implemented by
        ┌─────────────▼──────────────┐
        │   application/service      │   ConfigurationInspectionService
        └─────────────┬──────────────┘
                      │  depends on
        ┌─────────────▼──────────────┐
        │   domain/port/out          │   ConfigurationSourcePort
        └─────────────┬──────────────┘
                      │  implemented by
        ┌─────────────▼──────────────┐
        │  infrastructure/adapter/out│   SpringEnvironmentConfigurationAdapter
        └─────────────┬──────────────┘
                      │
            Vault-compatible store
```

Every dependency arrow points inward.

| Layer | Package | Knows about Spring? |
| --- | --- | --- |
| Domain | `domain/` | No |
| Application | `application/` | No |
| Infrastructure | `infrastructure/` | Yes |

`@SpringBootApplication(scanBasePackages = "com.cmn.service.infrastructure")`
restricts component scanning to the infrastructure package, so the inner layers
cannot acquire annotations by accident. They are wired explicitly in
`HexagonalWiringConfiguration`, the composition root.

The payoff is in the tests: `ConfigurationInspectionServiceTest` exercises the
application layer with a hand-written fake port — no Spring context, no
container, no mocking framework.

**One deliberate compromise:** the ports use `Mono`/`Flux`. Reactor is treated
as part of the language rather than a framework detail. Keeping the ports
synchronous would force the adapter to block, defeating WebFlux.

---

## Configuration

Only two files:

| File | Contents |
| --- | --- |
| `application.yml` | Common settings and how to reach Vault |
| `application-local.yml` | Developer-machine overrides |

There is **no** `application-dev.yml`, `-staging.yml` or `-prod.yml`. Everything
environment-specific lives in the store:

```text
cmn/config/<profile>    non-secret configuration
cmn/secret/<profile>    secrets
```

Those paths are derived from the active profile by Spring Cloud Vault itself:

```yaml
spring:
  config:
    import: vault://          # no path - defers to the kv settings below
  cloud:
    vault:
      kv:
        backend: cmn
        default-context: config       # -> cmn/config/<profile>
        application-name: secret      # -> cmn/secret/<profile>
        profile-separator: '/'
```

So **adding an environment means writing two documents to the store**, not adding
a file here:

```bash
cd ../openbao && PROFILE=staging ./env-to-bao.sh
```

### Profiles

Exactly one of `local`, `dev`, `staging`, `prod` must be active.
`Environment.fromActiveProfiles` throws otherwise — an instance can never start
without knowing which environment it is.

| Profile | Vault | Secrets required | Notes |
| --- | --- | --- | --- |
| `local` | optional | no | Falls back to the `dev` documents, debug logging, `env`/`configprops` exposed |
| `dev` | required | no | |
| `staging` | required | **yes** | |
| `prod` | required | **yes** | AppRole auth, never a root token |

"Secrets required" is enforced by `SecretsAvailabilityGuard` — see below.

### Environment variables

| Variable | Default | Purpose |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | *(none)* | Required |
| `VAULT_ADDR` | `http://localhost:8200` | Vault/OpenBao URL (`BAO_ADDR` also accepted) |
| `VAULT_AUTH` | `TOKEN` | `TOKEN` or `APPROLE` (`BAO_AUTH` also accepted) |
| `VAULT_TOKEN` | — | For `TOKEN` auth (`BAO_TOKEN` also accepted) |
| `VAULT_ROLE_ID` / `VAULT_SECRET_ID` | — | For `APPROLE` auth (`BAO_*` also accepted) |
| `VAULT_FAIL_FAST` | `true` | `local` overrides to `false` |
| `SERVER_PORT` | `8080` | |

---

## Running with AppRole

Production must not use the root token. Create a scoped identity:

```bash
cd ../openbao
./bao-approle.sh prod
set -a && . ./.openbao-approle-prod.env && set +a

cd ../cmn-service
VAULT_AUTH=APPROLE mvn spring-boot:run -Dspring-boot.run.profiles=prod
```

`bao-approle.sh` creates a read-only policy scoped to `cmn/config/prod` and
`cmn/secret/prod`, verifies the credentials work, and verifies they **cannot**
read another environment's secrets.

---

## Against a real HashiCorp Vault

Nothing in the service changes. Create the same structure in Vault and point
`VAULT_ADDR` at it.

```bash
export VAULT_ADDR=https://vault.internal:8200
vault login                       # or any auth method you already use

# Same KV v2 mount and layout the local stack uses
vault secrets enable -path=cmn kv-v2

vault kv put cmn/config/prod POSTGRES_USER=postgres AUTH_ISSUER_URI=...
vault kv put cmn/secret/prod POSTGRES_PASSWORD=... WORKFLOW_JWT_SECRET=...
```

A policy equivalent to the one `bao-approle.sh` writes — note the profile-less
paths, which Spring Cloud Vault probes first:

```hcl
path "cmn/data/config"          { capabilities = ["read"] }
path "cmn/data/secret"          { capabilities = ["read"] }
path "cmn/data/config/prod"     { capabilities = ["read"] }
path "cmn/data/secret/prod"     { capabilities = ["read"] }
path "cmn/metadata/config/prod" { capabilities = ["read", "list"] }
path "cmn/metadata/secret/prod" { capabilities = ["read", "list"] }
path "auth/token/renew-self"    { capabilities = ["update"] }
path "auth/token/revoke-self"   { capabilities = ["update"] }
```

```bash
vault policy write cmn-service-prod policy.hcl
vault auth enable approle
vault write auth/approle/role/cmn-service-prod \
    token_policies=cmn-service-prod token_ttl=1h token_max_ttl=4h
```

Then run the service:

```bash
export VAULT_ADDR=https://vault.internal:8200
export VAULT_AUTH=APPROLE
export VAULT_ROLE_ID=...
export VAULT_SECRET_ID=...
export VAULT_NAMESPACE=...          # Vault Enterprise / HCP only

java -jar cmn-service.jar --spring.profiles.active=prod
```

`VAULT_NAMESPACE` is ignored by OpenBao, which has no namespace concept, and by
Vault OSS.

---

## Endpoints

| Endpoint | Purpose |
| --- | --- |
| `GET /` | Service index |
| `GET /api/v1/environment` | Resolved environment, and whether Vault backed it |
| `GET /api/v1/configuration` | Full snapshot, secrets masked |
| `GET /api/v1/configuration/{key}` | One entry; 404 if absent |
| `GET /actuator/health` | Liveness/readiness |

```json
{
  "environment": "dev",
  "backedByVault": true,
  "satisfiesEnvironmentRequirements": true,
  "configCount": 46,
  "secretCount": 20,
  "countByOrigin": { "VAULT": 61, "LOCAL_FILE": 6 },
  "entries": [
    { "key": "POSTGRES_USER", "value": "postgres", "sensitive": false, "length": 8, "origin": "VAULT" },
    { "key": "POSTGRES_PASSWORD", "value": "********", "sensitive": true, "length": 22, "origin": "VAULT" }
  ]
}
```

### Masking

`ConfigurationKey.sensitive()` uses the same keyword list as
`../openbao/env-to-bao.sh`, so anything routed to `cmn/secret/<env>` during
migration is also masked on the way out.

Masking lives in the **domain**, not the web layer — `presentableValue()` is
what the handler serialises. An adapter cannot leak a value by forgetting to
mask it. An empty secret reports as empty rather than masked: knowing a secret
is unset is useful and reveals nothing.

---

## Build and test

```bash
mvn clean test       # 32 tests, no Spring context needed for most
mvn clean package
docker build -t cmn-service:latest .
```

> On Windows, do **not** set `MSYS_NO_PATHCONV=1` when invoking Maven from Git
> Bash — it breaks the launcher with
> `Could not find or load main class org.codehaus.plexus.classworlds.launcher.Launcher`.

---

## Vault vs OpenBao

### 1. The health indicator is off by default

```yaml
management.health.vault.enabled: ${VAULT_HEALTH_ENABLED:false}
```

This is the **only** behavioural difference between the two backends that this
service has to care about.

Spring Vault maps `performance_standby` to a primitive `boolean`. That field is
Vault **Enterprise**-only and OpenBao omits it from `/v1/sys/health`, so Jackson
fails with `Cannot map null into type boolean` and `/actuator/health` is
permanently `DOWN` on a healthy service. Only the indicator is affected;
reading configuration works normally.

Against **Vault Enterprise** the field is present and the indicator works, so
turn it on there:

```bash
VAULT_HEALTH_ENABLED=true
```

Vault **OSS** also omits the field, so leave it off there too.

### 2. `SecretsAvailabilityGuard` exists because Vault does not fail on missing paths

A non-`optional:` `vault://` import does **not** fail when the KV path is
absent. Spring Cloud Vault logs

```text
Vault location [cmn/config/staging] not resolvable: Not found
```

and carries on, and `spring.cloud.vault.fail-fast` covers only an unreachable or
sealed server. Without the guard, `staging` and `prod` would start on bundled
defaults and serve traffic with no real configuration.

The guard fails context refresh — before the HTTP listener accepts anything —
when an environment that requires secrets received none.

### A related trap: least-privilege policies must allow the profile-less probe

Spring Cloud Vault probes `cmn/data/config` and `cmn/data/secret` (no profile)
before the profile-specific paths. Under a root token those return 404 and are
ignored. Under a scoped policy that omits them they return **403**, which
`fail-fast: true` turns into a startup failure. `bao-approle.sh` grants read on
them for that reason; granting read on a path that does not exist discloses
nothing.

---

## Version notes

Spring Boot **4.2.0 does not exist** — only `4.2.0-M1`, a milestone. This uses
**4.1.1**, the latest GA.

Spring Cloud **2025.1.3** declares `<spring-boot.version>4.0.8</spring-boot.version>`,
so Boot 4.1.1 is one minor ahead of what it targets. The Boot parent wins for
every Boot-managed artifact and Spring Cloud contributes only its own modules
(`spring-cloud-vault-config`, `spring-vault-core`). Verified working against a
live OpenBao on all four profiles — but it is not an officially tested
combination. For an exactly-aligned stack, pin Boot to 4.0.8.
