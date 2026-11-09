# Environment variables

Four places. Neon is the odd one out: it supplies values rather than consuming
them.

| Where | Variables |
|---|---|
| Frontend, local | `NEXT_PUBLIC_API_MODE` |
| Frontend, Vercel | `NEXT_PUBLIC_API_MODE`, `API_ORIGIN` |
| Backend, local | `DB_PASSWORD`, `JWT_SECRET`, `COOKIES_SECURE` |
| Backend, Render | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `COOKIES_SECURE` |
| Neon | none |

Live URLs: frontend <https://screenbook.vercel.app>, API
<https://cinebook-67pa.onrender.com>.

Two variables have no default and the service will not start without them:
`JWT_SECRET` and `COOKIES_SECURE`. That is deliberate and explained below.

---

## One origin

The browser only ever talks to the frontend's origin. `next.config.ts` rewrites
`/api/*` to the Spring service:

```ts
const API_ORIGIN = process.env.API_ORIGIN ?? "http://localhost:8082";
```

Two consequences worth knowing before the rest of this document:

- `API_ORIGIN` is server-side, deliberately not `NEXT_PUBLIC_`. The browser
  never needs to know where the API actually lives.
- There is no CORS in this path, because there is no cross-origin request left
  to permit. Session cookies are first-party, which is the whole point: Safari
  and Chrome both block third-party cookies, so a cookie set by the API's own
  hostname would be dropped on the frontend's page.

`NEXT_PUBLIC_API_URL` is gone. `API_BASE_URL` in `apiConfig.ts` is now the empty
string and every request is relative.

## Frontend

```
NEXT_PUBLIC_API_MODE   mock | live
API_ORIGIN             where /api/* is forwarded, server-side only
```

`mock` means MSW intercepts requests in the browser, no backend is involved, and
`API_ORIGIN` is irrelevant. `live` means the rewrite forwards to a real service.

Local (`frontend/.env.local`, gitignored):

```
NEXT_PUBLIC_API_MODE=live
```

`API_ORIGIN` falls back to `http://localhost:8082`, so local needs nothing else.

Vercel (Project Settings, Environment Variables):

```
NEXT_PUBLIC_API_MODE=live
API_ORIGIN=https://cinebook-67pa.onrender.com
```

**`API_ORIGIN` is not optional on Vercel.** Without it the rewrite forwards to
`localhost:8082`, which on Vercel's edge is nothing at all, and every API call
fails in a way that looks like a frontend bug.

`NEXT_PUBLIC_*` values are substituted into the bundle at build time rather than
read at runtime, and `API_ORIGIN` is read when the rewrite config is evaluated,
which on Vercel is also build time. Changing either needs a dev-server restart
locally or a redeploy on Vercel. A page refresh will not do it.

## Backend

Every variable, with the default it falls back to:

| Variable | Default | Notes |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/screenbook` | JDBC form, not the `postgresql://` form Neon hands you |
| `DB_USERNAME` | `postgres` | |
| `DB_PASSWORD` | `postgres` | |
| `PORT` | `8082` | Render injects this; never set it yourself |
| `JWT_SECRET` | **none** | At least 32 bytes. The app refuses to start without it |
| `COOKIES_SECURE` | **none** | `true` anywhere served over HTTPS, `false` on local plain HTTP |
| `SPRINGDOC_ENABLED` | `true` | `false` hides Swagger UI and `/v3/api-docs` |
| `SCREENBOOK_CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Only for third-party clients of the public API, not for this frontend |

### The two with no default

`JWT_SECRET` has no development fallback on purpose. A default is exactly the
value that ends up signing production tokens the day someone forgets to set the
variable, and an app that will not start is a better failure than one that
starts insecure. HMAC-SHA256 needs a key at least as long as its output, so
anything under 32 bytes is rejected at startup, where it is impossible to miss,
rather than quietly weakening every token.

`COOKIES_SECURE` has no default for the same reason, and it is the subtler of
the two. A missing value would leave `sb_access` and `sb_refresh` without the
`Secure` attribute over HTTPS, silently, with nothing in the log and nothing
visibly wrong. `false` locally, because a `Secure` cookie is never set over
plain http and login would fail with nothing useful to read. `true` everywhere
else.

Generate a secret with:

```bash
openssl rand -base64 48
```

Never commit one. A secret that has been in a tracked file is burned even after
the commit is amended, so rotate it rather than trying to hide it.

### Local development

```
DB_PASSWORD=postgres
JWT_SECRET=<at least 32 bytes>
COOKIES_SECURE=false
```

Set these in the IntelliJ run configuration, or as user-level environment
variables with a terminal and IDE restart afterwards so both inherit them.

The test suite needs none of them. `src/test/resources/application-test.properties`
sets a fixed secret of its own so the suite does not depend on a developer
having exported anything, and sets `screenbook.cookies.secure=false`. A
profile-specific file outranks `application.properties`, so the placeholders
there are never evaluated during a test run.

### Render

```
DB_URL          jdbc:postgresql://ep-blue-star-ban44ub9.c-8.eu-central-1.aws.neon.tech/neondb?sslmode=require
DB_USERNAME     neondb_owner
DB_PASSWORD     (from the Neon connection string)
JWT_SECRET      (generated, never reused from anywhere else)
COOKIES_SECURE  true
```

`SCREENBOOK_CORS_ALLOWED_ORIGINS` is no longer needed for the frontend to work,
because the frontend is same-origin now. Keep it only to let browser clients on
other origins use the public read endpoints and guest booking. Signing in
cross-origin is impossible by design, since the CORS config sets
`allowCredentials(false)`.

The odd name is Spring Boot's relaxed binding: the property
`screenbook.cors.allowed-origins` is set by uppercasing it and turning dots and
dashes into underscores.

## Neon

Nothing is configured on Neon. It hands out a connection string in libpq form:

```
postgresql://USER:PASSWORD@HOST/DATABASE?sslmode=require&channel_binding=require
```

Which splits into the three `DB_*` values above, with two changes:

- Prefix the host with `jdbc:postgresql://` and drop the user and password from
  the URL, since JDBC takes them separately.
- Drop `channel_binding`. It is a libpq parameter and the JDBC driver does not
  understand it. Keep `sslmode=require`.

Use the direct endpoint, not the one with `-pooler` in the hostname. HikariCP
already pools connections inside the application, and Neon's pooler is
PgBouncer in transaction mode, which adds nothing here and can interfere with
the driver's server-side prepared statements.

The `.env` written at the repository root by `neon init` is read by nothing in
this project. Spring Boot does not read `.env` files.

## Order of operations

The old three-step CORS dance is gone. The frontend no longer needs the API to
know about it, so the first deployment is:

1. Deploy the API with the `DB_*` values, `JWT_SECRET` and `COOKIES_SECURE=true`.
   If either of the last two is missing the service will not start, which is the
   intended behaviour, and the Render log names the one that is missing. Note the
   Render URL.
2. Set `API_ORIGIN` to that URL on Vercel, with `NEXT_PUBLIC_API_MODE=live`, and
   deploy.
3. Confirm `https://<the Vercel domain>/api/showtimes` returns JSON.

Step 3 is the one to do deliberately rather than assume. It tests the proxy, not
the API: hitting the Render URL directly proves nothing about whether the rewrite
works, and the entire cookie design depends on that one path.

### Reading a failure at step 3

A rewrite with an absolute destination is compiled into a Vercel platform route
and proxied by Vercel, not by the Next server. That changes what the errors mean,
and it is worth knowing because it cost a day:

- **Vercel's own `404: NOT_FOUND`** page means `API_ORIGIN` is unset, so the
  platform is trying to proxy to the `http://localhost:8082` fallback and cannot
  resolve it. It is not a missing route and not a stale build.
- **Next's own "404: This page could not be found."** means the request reached
  the app, so the rewrite is absent from the deployed build.

Telling those two apart is the whole diagnosis. Compare against a path Next
definitely owns but does not have, such as `/definitely-not-a-route`: if that
returns Next's 404 while `/api/showtimes` returns Vercel's, the request is being
intercepted above the app and the destination is the problem.

Also delete `NEXT_PUBLIC_API_URL` from Vercel once `API_ORIGIN` is set. Nothing
reads it any more, and leaving the right URL under a name nothing reads is how
someone later concludes the API is configured when it is not.
