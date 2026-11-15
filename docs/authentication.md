# Authentication and profiles

[Back to the project overview](../README.md)

## Activation, notifications and recovery

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

## HTTP authentication

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
Logout does not revoke an already issued Access Token.

## View your profile

GET /api/v1/users/me requires a valid Access Token and an active account.
It returns HTTP 200 with id, email, firstName and lastName.
The user ID comes from the authenticated principal, not from request parameters.

Missing or invalid authentication returns HTTP 401 with
WWW-Authenticate: Bearer. The profile use case also checks that the account
still exists and is active when it loads the profile.

This endpoint performs two account reads: one during authentication and one
during profile loading. It does not lock the account against later changes.

## Update your profile

`PATCH /api/v1/users/me` accepts `firstName`, `lastName`, or both:

```json
{
  "firstName": "Adama",
  "lastName": "Ndiaye"
}
```

Send an Access Token in the Authorization header. The account ID comes from the
principal. A missing field keeps its current value. Explicit nulls, non-string
values and unknown fields are rejected. An empty object, blank names and names
longer than 100 Java string units are rejected with HTTP 400. The length rule is
the same as registration; it does not count user-perceived Unicode characters.

A successful request returns HTTP 200 with `id`, `email`, `firstName` and
`lastName`. Email, password hash and account status are not editable here.

The use case reads the account with a PostgreSQL write lock and checks that it is
active. It validates both final names before a targeted update of the names and
`updatedAt`, within the same transaction. It does not call the general user save
method. Redis is not involved in the profile update itself.

Domain, use-case, JSON and HTTP success/authentication tests cover this flow.
Repository tests check the targeted columns. Dedicated concurrent PATCH and
use-case rollback tests, plus HTTP 400/no-write tests, remain to be completed.
Do not treat the existing tests as proof of those transaction guarantees.

