# Dwell developer guide

Start here. This page explains how the system fits together and how to run it. Each service has its own guide covering its endpoints, how its main flows work, and what its tests prove.

| Guide | What it covers |
|---|---|
| [api-gateway](services/api-gateway.md) | Single entry point: JWT check, route roles, rate limiting, forwarding |
| [identity](services/identity.md) | Register, login, refresh and logout (Keycloak), roles, activating and deactivating users |
| [product-catalog](services/product-catalog.md) | Categories, attributes, products, variants, images (MinIO), Kafka image events |
| [common-logging](services/common-logging.md) | Shared JSON logging, correlation id, masking (used by all three services) |
| [Testing guide](testing/README.md) | How the tests are built, and what each of the 277 tests sets up, does and checks |
| [LOGGING.md](LOGGING.md) | Logging, Loki and Grafana, and the error-handling rules in depth |
| [WebclientUsage.md](WebclientUsage.md) | Calling another service so the correlation id is forwarded |

## Architecture

```
client ──(Bearer JWT)──▶ api-gateway :8081 ──(X-Gateway-Secret)──┬──▶ identity :8083 ──▶ Keycloak :8080
                          │  validate JWT                          │        │             (realm "Dwell")
                          │  match route, check roles              │        └──▶ Postgres (identity DB)
                          │  rate limit (Redis)                    │
                          │                                        └──▶ product-catalog :8084
                          └──▶ Redis :6379                                 ├──▶ Postgres (product-catalog DB)
                                                                           ├──▶ MinIO :9000 (images)
                                                                           └──▶ Kafka :9092 (image events)

all services ──JSON log files──▶ Grafana Alloy ──▶ Loki :3100 ──▶ Grafana :3001
```

- **Every client request goes through the gateway.** identity and product-catalog reject any request without the gateway's shared secret header (403), so the gateway's role checks can't be bypassed.
- **Keycloak is the source of truth for logins and roles.** identity talks to Keycloak and keeps a local `app_user` copy of each user, used for audit, active/inactive status and last login.
- **Every request has a correlation id** (`X-Correlation-ID`). It is in every log line, every response body and every Kafka message. Quote it when reporting a problem, and search for it in Grafana to see the whole request across all services.

| Service | Port | Stack | Database |
|---|---|---|---|
| api-gateway | 8081 | Spring Cloud Gateway (WebFlux), R2DBC | `gateway` (routes) + Redis |
| identity | 8083 (base path `/identity`) | WebFlux, R2DBC, WebClient | `identity` (`app_user`, `user_log_audit`) + Redis |
| product-catalog | 8084 | Spring MVC, JPA, Kafka, MinIO | `product-catalog` |
| common-logging | – | Library (Maven module) | – |

## Running locally

### 1. Infrastructure

| What | Port | Notes |
|---|---|---|
| PostgreSQL (services) | 5432 | Databases `gateway`, `identity`, `product-catalog`; user `tixly-ticket` |
| Keycloak + its Postgres | 8080 (+5438) | Realm `Dwell`, client `dwell-client` |
| Redis | 6379 | Rate limits (gateway), Keycloak admin token cache (identity) |
| Kafka (KRaft) | 9092 | Image events |
| MinIO | 9000 (console 9001) | Bucket `images` |
| Loki / Alloy / Grafana | 3100 / 12345 / 3001 | Optional: centralized logs |

> **Not in git yet:** the Docker Compose files (`docker/infra`, `docker/observability`), the run script (`scripts/dwell.sh`) and the Postman collection (`postman/`) are excluded by `.gitignore`. Ask the team for copies until they are committed.

With the files in place:

```bash
cd docker/infra && docker compose up -d            # Keycloak, Postgres, Redis, Kafka, MinIO
cd docker/observability && docker compose up -d    # Loki, Alloy, Grafana (optional)
```

### 2. Services

```bash
mvn install -DskipTests              # builds common-logging first, then the services
scripts/dwell.sh start all --build   # identity, product-catalog, api-gateway (in that order)
scripts/dwell.sh status
scripts/dwell.sh logs identity -f    # pretty-printed JSON logs
```

Without the script, run each service with `mvn -pl <service> spring-boot:run`. Start identity with `SPRING_PROFILES_ACTIVE=local` to create the first admin (`dwell_admin` / `Admin@12345`).

The main environment variables are listed in the root `.env.example`. Each guide lists the ones its service uses.

### 3. Gateway routes

Routes are **data**, not code: they live in the gateway's `routes` table and are managed with `/api/routes`. A path with no route is denied. A new environment needs these routes:

| Path | Method | Roles | Target |
|---|---|---|---|
| `/identity/api/auth/**` | any | `PUBLIC` | identity |
| `/identity/api/roles/**` | any | `ADMIN` | identity |
| `/identity/api/users/**` | any | `ADMIN` | identity |
| `/api/categories/**` | GET | `CUSTOMER,ADMIN` | product-catalog |
| `/api/categories/**` | any | `ADMIN` | product-catalog |
| `/api/v1/products/**` | GET | `CUSTOMER,ADMIN` | product-catalog |
| `/api/v1/products/**` | any | `ADMIN` | product-catalog |

See [api-gateway](services/api-gateway.md#managing-routes) for the request format.

## Tests

```bash
mvn test                         # everything (product-catalog's repository tests need Docker: Testcontainers)
mvn -pl identity test            # one module
mvn -pl identity test -Dtest=UserServiceImplTest
```

| Module | Tests | Kind |
|---|---|---|
| common-logging | 57 | Unit tests + Spring Boot auto-configuration tests |
| api-gateway | 23 | Unit tests |
| identity | 40 | Unit tests (Keycloak and the database are mocked) |
| product-catalog | 157 | Unit tests, `@WebMvcTest` controller tests, `@DataJpaTest` on a real PostgreSQL (Testcontainers) |

How each test works: [testing guide](testing/README.md).

Code is formatted with Spotless (Google Java Format). Run `mvn -pl <module> spotless:apply` before committing.

## Conventions

- **Response body:** `{"success": bool, "message": ..., "data": ..., "correlationId": ...}` (the gateway's own API also has `errorCode`).
- **Errors:** 4xx messages describe the client's input. 5xx always get a general message; the detail is only logged (WARN for 4xx, ERROR with stack trace for 5xx). See [LOGGING.md](LOGGING.md#errors-and-exceptions).
- **Secrets are never logged:** passwords, tokens, emails and phone numbers are masked as `****` in every log line.
