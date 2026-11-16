# CloudForge API

A Java backend project focused on authentication, account management and reliable
CI/CD. CloudForge is being built as an engineering platform; the current API
implements identity, sessions and user profiles, not cloud resource provisioning.

## What it demonstrates

- **Hexagonal architecture:** domain rules, application use cases and ports,
  with HTTP, PostgreSQL, Redis and SMTP adapters.
- **Authentication:** BCrypt passwords, validated JWTs, single-use Refresh Token
  rotation and email activation with OTP codes.
- **Data consistency:** PostgreSQL transactions and row locks, atomic Redis Lua
  operations, and explicit handling of failures between systems.
- **Testing:** unit tests, HTTP security tests and integration tests against
  dedicated PostgreSQL and Redis services.
- **Delivery:** a non-root Docker image and GitHub Actions checks, with OCI image
  transfer to GHCR without rebuilding during publication.

## Stack

Java 21 · Spring Boot 3.5 · Spring Security · PostgreSQL · Redis · Flyway ·
JUnit 5 · Mockito · JaCoCo · Docker · GitHub Actions

## Features and endpoints

| Method | Endpoint | Purpose |
| --- | --- | --- |
| POST | `/api/v1/identity/register` | Create a pending account |
| POST | `/api/v1/identity/verify-email` | Activate the account with an OTP |
| POST | `/api/v1/identity/resend-verification-code` | Request another activation code |
| POST | `/api/v1/identity/login` | Obtain Access and Refresh Tokens |
| POST | `/api/v1/identity/refresh` | Rotate the Refresh Token |
| POST | `/api/v1/identity/logout` | Revoke the supplied Refresh Token |
| GET | `/api/v1/users/me` | Read the authenticated user's profile |
| PATCH | `/api/v1/users/me` | Update first name, last name, or both |

Profile routes require `Authorization: Bearer <accessToken>` and an active
account. Logout does not revoke an Access Token that has already been issued.

## Architecture

```text
src/main/java/io/cloudforge/
├── identity/
│   ├── domain/          User state and business rules
│   ├── application/     Use cases, commands and outbound ports
│   ├── api/             Controllers, DTOs and HTTP security
│   └── infrastructure/  Persistence, JWT, hashing and email adapters
└── shared/              Application configuration
```

Use cases depend on repository and service ports. Infrastructure adapters
implement those ports; controllers translate HTTP requests into application calls.

## Run locally

**Prerequisites:** Docker Engine and Docker Compose. Java 21 is also needed to
run Maven commands on the host.

```bash
cp .env.example .env
openssl rand -hex 32
```

Edit `.env`: set `POSTGRES_PASSWORD` and paste the generated value into
`JWT_SECRET`. If a development database volume already exists, keep its original
credentials. Never commit `.env` or delete a volume to fix a credential mismatch.

```bash
docker compose up -d --build --wait postgres redis mailpit api
```

- API: <http://127.0.0.1:8081>
- Swagger UI: <http://127.0.0.1:8081/swagger-ui.html>
- Health: <http://127.0.0.1:8081/actuator/health>
- Activation emails in Mailpit: <http://127.0.0.1:8025>

To try the flow: register, read the OTP in Mailpit, activate the account, then
use the returned Access Token to call a profile route.

For Maven/IntelliJ startup on port **8080**, environment variables and service
ports, see [Local development](docs/local-development.md).

## Run tests

With `.env` configured and Docker running:

```bash
docker compose --profile test up -d --wait postgres-test redis-test
./mvnw clean verify -Dspring.profiles.active=test
```

Tests use PostgreSQL **5433 / cloudforge_test** and Redis **6390 / database 1**.
A shared guard rejects common configuration mistakes before normal persistence
initialization. See its [scope and setup requirements](docs/local-development.md#run-tests),
including how to load customized test credentials.

Reports: `target/surefire-reports` and `target/site/jacoco/index.html`.

## CI/CD

Pull requests to `develop` and `main` run tests and validate a Docker OCI image.
They do not publish. Successful pushes to those branches can publish the same
validated image to GHCR, with branch-specific SHA tags and moving tags.

The workflow checks test and coverage reports. It has no minimum coverage
percentage gate and no SonarCloud integration.
[Pipeline details and publication limits](docs/ci-cd.md).

## Scope and limits

- Local SMTP uses Mailpit; production email delivery is not configured.
- PostgreSQL and Redis do not share a transaction. Recovery paths are documented.
- Dedicated tests cover concurrent partial profile updates, rollback after a SQL
  write, and HTTP 400 responses without repository writes.
- [Main pipeline run 43693528014](https://github.com/alaminediassy/cloudforge-api/actions/runs/43693528014)
  passed on 2026-11-15, including artifact transfer and GHCR publication. This
  validates that run, not every future publication or timeout recovery.

## Further reading

- [Local development, environments and tests](docs/local-development.md)
- [Authentication, profile contracts and failure recovery](docs/authentication.md)
- [CI/CD, OCI integrity and GHCR publication](docs/ci-cd.md)
