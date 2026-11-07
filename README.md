# CloudForge API

## Description
CloudForge API est le backend de la plateforme d'ingénierie CloudForge. Il fournit actuellement les services liés à la gestion des identités et des sessions utilisateurs.

## Architecture
Le projet respecte les principes de l'Architecture Hexagonale (Ports et Adaptateurs). Le code est structuré de manière à isoler le domaine métier de l'infrastructure technique et des points d'entrée de l'API.

## Stack technique
- Java 21
- Spring Boot
- Maven
- PostgreSQL
- Redis
- JSON Web Tokens (JJWT)

## Local environment

Use the branch containing these changes (`fix/r-01-local-environment` until it is
merged into `develop`). Java 21 is required for Maven and IntelliJ. Docker Engine
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

### Run everything with Docker Compose

```bash
docker compose up -d --build --wait postgres redis mailpit api
```

Compose explicitly selects `dev` for the API. PostgreSQL and Redis use their
Docker service names inside the network. The API is available at
`http://127.0.0.1:8081`; check `/actuator/health` and `/swagger-ui.html` there.
The image build skips test execution; run the test procedure separately.

### Run the API with Maven or IntelliJ

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

### Run tests

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

### Ports and data

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

Use `docker compose --profile test stop` to stop services while preserving data.
Do not use `down -v`. Keep the Compose project name and directory unchanged when
reusing existing named volumes. Health checks verify service readiness, not a
complete registration or login flow.

The `prod` profile must be selected explicitly and requires `DB_URL`,
`DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET` and appropriate Redis connection settings
through Spring environment variables. This Compose file is for local development,
not a production deployment configuration.

### Activation, notifications and recovery

`POST /api/v1/identity/register` returns `201 Created` after PostgreSQL confirms
account creation. The response body is unchanged: `201` confirms the account,
not email delivery. The application stores the OTP hash before calling SMTP.
A delivery failure after creation still returns `201`; the account remains
`PENDING`, and the client can request another code after the two-minute cooldown.
No email address, OTP, email body or raw SMTP exception is logged.

A Redis storage failure is different: registration returns an error (503 for a
connection failure), even if the account was already created. Recover through
`POST /api/v1/identity/resend-verification-code`, not by registering again.
No notification is attempted without a successful Redis acknowledgement. An
ambiguous Redis timeout may leave a stored code/cooldown; resend after the
cooldown still recovers. A cooldown without a usable code is also respected
until it expires, then a resend repairs the state.

OTP validity is seven minutes. The third incorrect attempt in a seven-minute
window blocks verification and issuance for fifteen minutes and invalidates the
code. Resending does not reset an existing attempts window. Two short Redis Lua
scripts make issuance and verification/consumption atomic against concurrent
requests. A successful comparison removes the code in the same operation; a
second request cannot consume it again. The cooldown remains after consumption.
The current multi-key scripts target standalone Redis, not Redis Cluster.

A PostgreSQL row lock serializes OTP preparation and activation for each account.
`SendActivationOtpUseCase` is a nontransactional orchestrator. It calls the separate
transactional `PrepareActivationOtpUseCase` to check account status and store the
code, waits for the transaction to finish, then calls SMTP without holding the
issuance transaction's row lock or JDBC connection. A preparation commit failure
prevents notification; Redis may still retain the code and cooldown, so resend
after the cooldown recovers. SMTP has explicit three-second connection, read and
write timeouts; these are per-operation timeouts, not a total delivery deadline.

There is deliberately no atomic boundary between transaction completion and
notification. The account can be activated, or a later resend can replace the code,
before an earlier email is delivered. Such an email can contain a consumed or
superseded code. Only the current Redis code can be verified, and an active account
cannot be activated again. Mail delivery order does not define code validity. A
failed or late notification never restores or overwrites a code. No outbox,
broker, durable delivery retry or distributed transaction is implemented.

Verification repairs a legacy attempt counter with no TTL by setting the normal
seven-minute attempt expiry atomically. Its value is preserved, and an existing
TTL is never extended. This prevents a permanent legacy counter from repeatedly
recreating fifteen-minute blocks after each new code issuance.

If PostgreSQL activation fails after OTP consumption, the SQL transaction rolls
back and the consumed code is not restored. Request a new code after the cooldown.
A successful verification response is sent only after commit. A Redis Refresh
Token written before a failed SQL commit may remain until its seven-day expiry;
it is not returned to the client. Redis stores its hash, not the usable JWT, and a
PENDING account cannot refresh a session. If the raw JWT were obtained separately
and the account later became ACTIVE, that JWT could still be accepted until expiry.
No atomicity or guaranteed cleanup across PostgreSQL and Redis is claimed.
If a commit succeeds but the HTTP response is
lost, use login to recover the now-active account.

Mailpit captures messages locally without delivering them externally. Its UI
contains activation codes: keep its ports bound to loopback and do not expose it.
The SMTP adapter is enabled only for `dev` and `test`, permits only loopback or
the `mailpit` service hostname, and uses `no-reply@cloudforge.test` as sender.
No production email provider is configured; other profiles cannot deliver
activation emails. SMTP transport debugging is disabled. Failed or unacknowledged
SMTP delivery retains the stored code: SMTP may have accepted a message before
the connection failed. A later resend replaces that code after the cooldown.

Refresh Token rotation still consumes the old token atomically using Redis
`GETDEL`. If PostgreSQL access or storage of the replacement fails afterwards,
the old token cannot be reused: log in again. Logout revokes only the supplied
Refresh Token, is idempotent when Redis is available, and returns an error if
revocation cannot be confirmed. No global session revocation is implemented.

### HTTP authentication

Send an Access Token in `Authorization: Bearer <token>` for protected routes.
The six existing Identity routes are public only for POST requests, including
logout, which keeps its existing Refresh Token body contract. Read access to
Swagger/OpenAPI and GET/HEAD `/actuator/health` remains public. Other Actuator
paths are denied, and only the health endpoint is exposed, without details.
All other routes require authentication. No roles or permissions are added.

Tokens retain their existing uppercase `ACCESS` and `REFRESH` type claims.
Verification checks the signature, expected HMAC algorithm, issuer, mandatory
expiration and canonical UUID subject. The algorithm follows the existing key
selection: HS256 for 32–47 UTF-8 bytes, HS384 for 48–63, HS512 for 64 or more.
Refresh Tokens cannot authenticate HTTP requests. Signing keys and token lifetimes
are validated at startup. Changing the key changes token validity.

A valid Bearer triggers one user lookup in PostgreSQL to require an existing,
active account. This adds database latency and load to authenticated requests;
database availability becomes an authentication dependency. There is no user
status cache. A recognized database outage returns a generic 503 and does not
grant access. Missing, invalid, unknown-user and inactive-user authentication
returns the same 401 response. Authenticated requests to a forbidden route return
403. An explicitly supplied invalid Authorization header is rejected even on
     public routes: omit a stale Access Token header when calling refresh or login.

The principal contains only the user UUID. Credentials and JWT text are not
stored in it. Authentication is request-scoped: HTTP sessions, form login, Basic
authentication and request caching are disabled. Query parameters and cookies
are not token transports. CSRF protection is disabled for this explicit Bearer
header API; revisit this choice if cookie authentication is introduced.
Logout does not revoke an already issued Access Token. No new private business
endpoint has been added; authentication is exercised through test-only routes.

### Local CI checks

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

## CI/CD and quality

The workflow runs on pushes to `develop` and `main`, and pull requests targeting
those branches. It separates three jobs:

1. **Build and Test:** Java 21 and Maven Wrapper `clean verify` with the `test`
   profile, PostgreSQL on port 5433 and dedicated Redis on port 6390. Test
   skipping and ignored failures are explicitly disabled. SMTP tests use a
   loopback capture server; no email provider or Mailpit service is needed in CI.
   Python tests check publication rules and allowed OCI archive paths. The report
   guard verifies mandatory test suites, compiled classes and JaCoCo XML coverage.
2. **Validate Docker Image:** after successful tests, build one Linux/amd64 OCI
   image without pushing or registry login. Verify the archive and upload it as
   a three-day artifact from the same run. The image has explicit Git revision
   and repository source labels. BuildKit attestations are disabled for this
   single-manifest transport; labels and checksums are integrity checks, not
   signed provenance.
3. **Publish GHCR Image:** requires successful test and Docker jobs. Only pushes
   to `develop` or `main` in `alaminediassy/cloudforge-api` qualify. Pull
   requests, other repositories and failed, cancelled or skipped prerequisites
   cannot publish. Download and verify the OCI archive, then transfer it with
   Skopeo `--preserve-digests`, without rebuilding. Tags are
   `develop-sha-<full Git SHA>` or `main-sha-<full Git SHA>`, with `develop` and
   `latest` as the respective moving tags.

The report guard rejects missing or malformed reports, failed or skipped tests,
missing mandatory security/OTP/SMTP suites, missing compiled classes and empty
JaCoCo line coverage. This is a report-presence and execution check, not a
minimum percentage coverage threshold or a substitute for code review.

Fork and Dependabot pull requests can run the test and Docker validation jobs
without registry publication credentials. No `pull_request_target` workflow is
used to grant secrets to untrusted pull request code.

The default GitHub token permission is `contents: read`. Only the publish job
receives `packages: write`; checkout does not persist credentials. Actions are
pinned to verified commit SHAs and need deliberate updates. Maven dependency
caches use GitHub's branch/PR cache scope; Docker cache writes are limited to
trusted pushes. The validated OCI image artifact expires after three days.
Test data must remain synthetic.

The Dockerfile still skips tests during its independent image build: mandatory
tests already run in the upstream job. Only the Docker job builds an image; the
publish job copies its archive, verifies it and uses Skopeo to transfer it.
The archive's SHA-256 covers the exact tar bytes. The OCI digest covers the image
manifest, which references the config and layers by digest. They are different
identifiers and are checked separately against the Docker job's outputs. The
verifier checks every referenced blob, its size, the platform and revision labels.
A changed archive, manifest, provenance or registry digest fails publication; there
is no fallback rebuild or format conversion. Base tags can affect a later build,
but cannot change the artifact already transferred between these two jobs.

The Docker context excludes `.env` files, and the Dockerfile copies only Maven
inputs and source code. No CI secret is passed to the build or uploaded with the
image. The production image contains the application JAR, not test reports.
Registry authentication is temporary, passed through stdin, and kept outside the
image artifact. Skopeo is installed from the Ubuntu runner's package repositories;
its release is not independently pinned. Unsupported digest preservation fails
closed. An HTTP redirect to blob storage does not forward registry credentials.

### Publication coordination and remaining limits

The entire publish job uses concurrency group
`ghcr-publication-${github.repository}-${github.ref_name}` with
`cancel-in-progress: false`. No automatic cancellation of an active publisher is
requested. GitHub does not guarantee commit ordering: before any upload and again
immediately before mobile promotion, `git ls-remote` must return the candidate SHA
as the remote branch head. A failed read or a stale revision fails the job.

The current mobile image must have a valid source label and full Git revision.
That revision must equal or be an ancestor of the candidate in the full checkout.
Missing history, unknown provenance and divergent or older candidates are refused.
A missing tag is accepted only for an explicit registry `MANIFEST_UNKNOWN` response
followed by a successful repository read. Authentication failures or an unknown
repository are not treated as an empty registry. The first publication of a missing
GHCR package has not been validated. A `NAME_UNKNOWN` response stops publication;
it does not trigger automatic package creation. Initial setup needs a separately
authorized remote check. Legacy multi-platform images are
read through their single Linux/amd64 manifest. Unverifiable legacy metadata needs
manual investigation; it is never silently overwritten.

Branch-qualified tags prevent develop/main naming collisions. Tags are still
mutable registry references; use `ghcr.io/alaminediassy/cloudforge-api@sha256:...`
to identify an exact image. This publisher refuses an existing version tag with a
different digest. It also refuses a different digest for an already published same
commit. Reusing the original verified artifact is idempotent; rebuilding the same
commit may produce a different image and is not silently accepted.

The version tag is transferred first and verified. The mobile tag is re-read to
detect observed outside changes, then the Git head is checked and only the exact
verified manifest bytes are promoted. There is no second image build, automatic
promotion retry, persistent promotion state or distributed lock.

Two limits remain explicit:

- Git can advance between the last head check and registry promotion. The mobile
  tag may temporarily lag behind Git; this does not authorize an already stale
  candidate when the last check runs.
- After a timeout, cancellation or interrupted push, a remote write can remain
  uncertain or complete late. A failed job does **not** prove that a remote tag
  stayed unchanged. GitHub concurrency alone does **not** guarantee absolute
  non-regression in this case. Inspect the remote tag/digest and prior run before
  authorizing a retry; there is no automated recovery or rollback.

Normal successful coordinated publications reject older/divergent revisions,
including runs finishing out of commit order. Manual writers and workflows using
another concurrency group are outside this coordination. Re-reading detects only
changes visible before the check, not an atomic compare-and-swap. Any digest
mismatch or unavailable metadata produces failure, never a forced promotion.
If only the version upload completed, a retry using the same artifact can verify
and reuse it; a new workflow build with another digest is refused.

Local checks cannot prove that GHCR accepts the token or that the hosted
publication workflow will succeed. Repository rules and publication behavior
require verification through an authorized GitHub Actions run.
This workflow does not configure branch protection or prevent a repository writer
from changing the workflow itself. No remote settings are changed by these files.

## Fonctionnalités actuelles
- Inscription utilisateur avec mot de passe haché.
- Vérification d'adresse email via code OTP.
- Authentification par email et mot de passe.
- Gestion des sessions avec Access Token et Refresh Token (JWT).
- Rotation sécurisée des Refresh Tokens.
- Déconnexion avec révocation de session.
