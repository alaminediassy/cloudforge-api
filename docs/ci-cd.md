# CI/CD and publication

[Back to the project overview](../README.md)

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

## Publication coordination and remaining limits

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
