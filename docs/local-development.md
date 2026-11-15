# Local development and tests

[Back to the project overview](../README.md)

Java 21 is required for Maven and IntelliJ. Docker Engine
and Docker Compose are required for the database services and the containerized API.
The Maven Wrapper selects Maven 3.9.16, including inside the Docker build.

Copy `.env.example` to `.env`, then edit the placeholders locally. Do not commit
`.env`. Generate a development signing key with `openssl rand -hex 32` and put the
result in `JWT_SECRET`. The application uses the resulting string as UTF-8 key
material; no Base64 decoding is performed.

If the PostgreSQL development volume already exists, set `POSTGRES_USER`,
`POSTGRES_PASSWORD` and `POSTGRES_DB` to its existing values. Changing environment
variables does not update an initialized database. Do not delete the volume to
resolve a credential mismatch.

| Variable | Purpose |
| --- | --- |
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | Development database and credentials; shared by Compose and the dev profile |
| `TEST_POSTGRES_DB`, `TEST_POSTGRES_USER`, `TEST_POSTGRES_PASSWORD` | Dedicated test database; local-only defaults are provided |
| `JWT_SECRET` | Generated development signing key; required for the API |
| `SPRING_PROFILES_ACTIVE` | Explicit host application profile; use `dev` locally |
| `SMTP_HOST`, `SMTP_PORT` | Optional local SMTP settings; default `localhost:1025`. Compose overrides these with `mailpit:1025` |
| `TEST_SMTP_HOST`, `TEST_SMTP_PORT` | Optional manual test SMTP settings; default `localhost:1026` |

Compose reads `.env` for interpolation. Maven and IntelliJ do not load this file
automatically. Keep values shell-compatible if using the shell loading procedure
below; quote values containing shell metacharacters.

## Run everything with Docker Compose

```bash
docker compose up -d --build --wait postgres redis mailpit api
```

Compose explicitly selects `dev` for the API. PostgreSQL and Redis use their
Docker service names inside the network. The API is available at
`http://127.0.0.1:8081`; check `/actuator/health` and `/swagger-ui.html` there.
The image build skips test execution; run the test procedure separately.

## Run the API with Maven or IntelliJ

Start only the dependencies:

```bash
docker compose up -d --wait postgres redis mailpit
```

Load your own trusted local file into the shell, then start the API:

```bash
set -a
. ./.env
set +a
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

For IntelliJ, select JDK 21, use the Maven Wrapper for Maven imports/runs, set the
active Spring profile to `dev`, and supply the development variables from the
table in the run configuration. Do not commit a run configuration containing secrets.

Redis uses host port `6380` to avoid conflicts with a locally installed Redis.
Containers still connect to `redis:6379`. Tests and CI use a separate Redis on `6390`.

The host API uses `localhost:5432` for PostgreSQL and `localhost:6380` for Redis.
It is available at `http://localhost:8080`; check `/actuator/health` and
`/swagger-ui.html`. Stop the containerized API when switching to this mode to
avoid unintentionally running two application instances against the same data.

## Run tests

With `.env` prepared as above, start the dedicated test database and Redis:

```bash
docker compose --profile test up -d --wait postgres-test redis-test
```

If you customized `TEST_POSTGRES_*`, load `.env` into your shell using the procedure
above. Then run:

```bash
./mvnw clean verify -Dspring.profiles.active=test
```

The Spring context test also declares `@ActiveProfiles("test")`, including when
run directly from IntelliJ. PostgreSQL tests use `localhost:5433` and a separate
volume, `postgres_test_data`. Tests use database 1 on the dedicated Redis instance
at `localhost:6390`; development keeps database 0 on `localhost:6380`.
A shared Spring Test guard checks the effective connection properties before normal
beans are created, including DataSource, Redis and Flyway. It requires only the
`test` profile, PostgreSQL on `localhost:5433/cloudforge_test` and Redis on
`localhost:6390`, database `1`. The equivalent `127.0.0.1` host is also accepted.
Keep `TEST_POSTGRES_DB=cloudforge_test`. Database credentials may be configured.
The guard rejects other destinations, JDBC URL parameters, separate Flyway
connections, Hikari overrides and Redis URL, Sentinel or Cluster settings.

This is a check for common configuration mistakes in the current Spring Test
setup, including JPA slices. It is not a security boundary for arbitrary test code.
Stable `@DynamicPropertySource` overrides are checked, but changing suppliers,
`DynamicPropertyRegistrar`, custom connection beans and later environment changes
are outside its scope. Direct JDBC calls and contexts created without Spring Test
are also outside its scope. A local port or tunnel does not prove server identity.
Do not introduce these mechanisms without reviewing the guard.

The OTP integration suite also checks the connected database name and Redis
settings before its fixtures. Tests remove only their own rows and keys, or roll
back their transactions. No `FLUSHALL`, development data reset or development
service interruption is needed.

SMTP tests use a loopback-only capture server and do not need Mailpit or internet
access. The test profile disables the SMTP Actuator health contributor for this
reason; development still checks Mailpit. For manual test email inspection, run
`docker compose --profile test up -d mailpit-test` and open `http://localhost:8026`.
The test-profile API sends to that separate capture service on port 1026.

The test profile supplies a public signing-key fixture independent of `JWT_SECRET`.
Never use that fixture outside tests. Do not override test connections with
`SPRING_DATASOURCE_*` environment variables pointing to development databases.

The current Mockito tests can also run without database services:

```bash
./mvnw -Dtest='*UseCaseTest' verify
```

This subset excludes the Spring context test and does not validate the full local
setup. Reports are written to `target/surefire-reports` and `target/site/jacoco`.

## Ports and data

| Service | Host endpoint | Data |
| --- | --- | --- |
| Development PostgreSQL | `127.0.0.1:5432` | Existing `postgres_data` volume |
| Test PostgreSQL | `127.0.0.1:5433` | Separate `postgres_test_data` volume |
| Development Redis | `127.0.0.1:6380` | No explicit persistent volume; sessions may be lost on recreation |
| Test Redis | `127.0.0.1:6390` | Dedicated instance, independent of development |
| Development Mailpit | SMTP `127.0.0.1:1025`, UI `127.0.0.1:8025` | Local email capture; no relay configured |
| Optional test Mailpit | SMTP `127.0.0.1:1026`, UI `127.0.0.1:8026` | Separate local email capture |
| Containerized API | `127.0.0.1:8081` | Uses development services |
| Host API | `localhost:8080` | Uses development services |

Use `docker compose --profile test stop postgres-test redis-test` to stop only
the test database services while preserving data.
Do not use `down -v`. Keep the Compose project name and directory unchanged when
reusing existing named volumes. Health checks verify service readiness, not a
complete registration or login flow.

The `prod` profile must be selected explicitly and requires `DB_URL`,
`DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET` and appropriate Redis connection settings
through Spring environment variables. This Compose file is for local development,
not a production deployment configuration.

## Local CI checks

Java 21, the Maven Wrapper, Docker Engine and Docker Compose are needed for the
full Java suite. Python 3 runs the CI script tests. Actionlint checks the workflow.
Docker Buildx with an OCI exporter is needed to repeat the image export check.
Skopeo is used by the publication job; the Python tests do not require it.

```bash
./mvnw -B -ntp clean verify -Dspring.profiles.active=test
python3 .github/scripts/test-publication.py
python3 .github/scripts/test-oci-artifact.py
python3 .github/scripts/verify-test-reports.py
actionlint .github/workflows/ci.yml
```

The report check needs a successful full Java test run. These commands do not
publish an image. Local success does not validate GitHub artifact transport or
GHCR permissions.
