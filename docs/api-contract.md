# ScreenBook API Contract

The document both sides are built against. Written before either side was
implemented, so the Next.js client and the Spring Boot service agree by
construction rather than by discovery.

- Base path: `/api`
- All timestamps ISO-8601 UTC with a trailing `Z`
- All money in integer minor units (pence), never floats
- Errors use RFC 9457 problem details (`application/problem+json`)
- Hold TTL: 300 seconds
- Up to 3 seats per hold as a guest, 6 signed in, claimed all or nothing
- `Idempotency-Key` honoured on `POST /api/bookings`, so a retry returns the
  original booking rather than a conflict
- Accounts are optional. Every booking endpoint works signed in or not, which
  is what guest checkout means
- Sessions are httpOnly cookies, never an `Authorization` header and never a
  token in a response body
- Unsafe requests to `/api/auth/refresh`, `/api/auth/logout` and `/api/me/**`
  carry a CSRF token

Thirteen endpoints. Seven are the booking flow: four doing the work, two `GET`s
that exist so the client's URLs survive a page refresh, one releasing a hold
early. The other six are accounts, added in wave 3.

---

## 1. `GET /api/showtimes`

List all showtimes.

**200**

```json
[
  {
    "id": 1,
    "filmTitle": "The Long Quiet",
    "screenName": "Screen 1",
    "startsAt": "2026-11-02T19:30:00Z",
    "runtimeMinutes": 128,
    "priceMinor": 1250,
    "currency": "GBP",
    "seatsAvailable": 87,
    "seatsTotal": 120
  }
]
```

No pagination. The dataset is small and bounded by what a cinema screens in a week.

`priceMinor` here is the *lowest* price for the screening, which is the Normal
tier, since Normal has an uplift of zero. It exists so the list can show "from
£11.50" without fetching 120 seats per showtime. The authoritative per-seat
price comes from the seat map.

---

## 2. `GET /api/showtimes/{showtimeId}/seats`

The seat map for one showtime.

**200**

```json
{
  "showtimeId": 1,
  "seats": [
    { "id": 1001, "rowLabel": "A", "seatNumber": 1, "status": "AVAILABLE",
      "tier": "NORMAL", "tierName": "Normal", "priceMinor": 1150, "currency": "GBP" },
    { "id": 1061, "rowLabel": "F", "seatNumber": 1, "status": "HELD",
      "tier": "PREMIUM", "tierName": "Premium", "priceMinor": 1400, "currency": "GBP" },
    { "id": 1097, "rowLabel": "I", "seatNumber": 1, "status": "BOOKED",
      "tier": "EXECUTIVE", "tierName": "Executive", "priceMinor": 1650, "currency": "GBP" }
  ]
}
```

**404** unknown showtime.

No row or column counts are sent. The client groups on `rowLabel` to build the
grid, so the layout is described in one place rather than two.

`tier` is one of `NORMAL`, `PREMIUM`, `EXECUTIVE`, ordered front of house to
back. The rows are fixed by the seeded layout: A to E Normal, F to H Premium,
I to J Executive. `tierName` is the display label, sent so the client does not
hold its own mapping from code to human text.

`priceMinor` is this seat's price for this showtime, resolved server-side as the
showtime's base price plus the tier's uplift. The client never does that
arithmetic, which is what stops the displayed price and the charged price from
drifting apart.

| Tier | Uplift over base |
|---|---|
| `NORMAL` | 0 |
| `PREMIUM` | +250 |
| `EXECUTIVE` | +500 |

`status`, `tier` and `priceMinor` all come from one view, `seat_availability`,
so the seat map and the claim path cannot disagree about a seat:

```sql
CREATE OR REPLACE VIEW seat_availability AS
SELECT st.id         AS showtime_id,
       s.id          AS seat_id,
       s.row_label   AS row_label,
       s.seat_number AS seat_number,
       CASE
         WHEN EXISTS (SELECT 1 FROM booking b
                       WHERE b.showtime_id = st.id
                         AND b.seat_id     = s.id)
           THEN 'BOOKED'
         WHEN EXISTS (SELECT 1 FROM seat_hold h
                       WHERE h.showtime_id = st.id
                         AND h.seat_id     = s.id
                         AND h.booked_at   IS NULL
                         AND h.released_at IS NULL
                         AND h.expires_at  > now())
           THEN 'HELD'
         ELSE 'AVAILABLE'
       END           AS status,
       s.tier                               AS tier,
       t.display_name                       AS tier_name,
       st.price_minor + t.price_delta_minor AS price_minor,
       st.currency                          AS currency
FROM showtime st
JOIN seat      s ON s.screen_id = st.screen_id
JOIN seat_tier t ON t.code      = s.tier;
```

`status` is computed per request, never stored on a seat. The
`h.expires_at > now()` predicate is doing the real work: an expired hold reads
as AVAILABLE the instant it expires, with no sweeper involved. A scheduled job
to delete old hold rows is then housekeeping, not correctness.

---

## 3. `POST /api/holds`

Claim one to six seats for 300 seconds, all or nothing.

**Request**

```json
{ "showtimeId": 1, "seatIds": [1002, 1003] }
```

`seatIds` carries 1 to 3 entries as a guest and 1 to 6 signed in, in the order
the customer picked them, with no seat repeated. The cap is enforced by the
server, not just the UI: a cap that only exists in the client is not a cap.

The higher cap is the one concrete benefit of an account, and it is deliberately
a modest one. A guest is anonymous, so six seats held by a guest is six seats
anyone can tie up leaving nothing behind; an account at least attaches the
behaviour to something.

A repeated seat is a client bug, not a conflict, so `[5, 5, 7]` is rejected
rather than quietly collapsed to `[5, 7]`. Deduping would hide the bug in
whatever built the request and make "how many seats did you ask for" ambiguous
in every message that follows.

**The seats must be adjacent:** all in one row, with no gaps. A group books in
order to sit together, so scattered seats are not a booking anyone wants, and
allowing them leaves the house full of unsellable single gaps.

Aisles deliberately do not break a run. Seats 3 and 4 have a walking aisle
between them and still count as neighbours, because treating the aisle as a
break would confine every group of four or more to the six-wide centre block.
Two people stepping over an aisle is a better outcome than a family of five
being told there is nowhere to sit.

The cost of this rule is worth stating: on a busy screening where no row has
six free seats in a row, a group of six cannot book at all. A softer rule, free
choice but no orphan seats left stranded, would sell more seats and take more
code. This project took the strict version deliberately.

**201**, with `Location: /api/holds/{holdId}`

```json
{
  "holdId": "0199c3a4-7f21-7b8e-9d44-1a2b3c4d5e6f",
  "showtimeId": 1,
  "seats": [
    { "seatId": 1002, "rowLabel": "A", "seatNumber": 2, "tier": "NORMAL", "priceMinor": 1150 },
    { "seatId": 1003, "rowLabel": "A", "seatNumber": 3, "tier": "NORMAL", "priceMinor": 1150 }
  ],
  "totalMinor": 2300,
  "currency": "GBP",
  "expiresAt": "2026-10-31T08:12:00Z",
  "ttlSeconds": 300
}
```

One hold covers every seat in it. There is no partial hold, so a hold either
expires whole or converts whole, and the TTL is one deadline rather than one per
seat.

`totalMinor` is the sum of the per-seat prices, computed server-side for the
same reason each seat's price is: the client should never be the thing that
decides what a basket costs.

Both `expiresAt` and `ttlSeconds` are returned. The first is authoritative and
what gets displayed. The second lets the browser count down against its own
clock, so a user whose machine clock is minutes out does not see a timer that
starts at zero.

**409** one or more seats already held or booked. Nothing was claimed:

```json
{
  "type": "https://screenbook.dev/problems/seats-unavailable",
  "title": "Seats unavailable",
  "status": 409,
  "detail": "2 of the 4 seats you chose have just gone.",
  "instance": "/api/holds",
  "unavailableSeats": [
    { "seatId": 1061, "rowLabel": "F", "seatNumber": 1, "status": "BOOKED" },
    { "seatId": 1062, "rowLabel": "F", "seatNumber": 2, "status": "HELD" }
  ]
}
```

This shape was designed before either side was built, because the client's
conflict UI and the server's rollback both depend on it. Four decisions in it:

- **409, not 207 Multi-Status.** 207 is a WebDAV code and it implies partial
  success. The claim is all or nothing, so the request wholly failed and 409
  says exactly that.
- **Only the lost seats are listed**, in the order they were requested. The
  client knows what it asked for, so requested minus unavailable is the set
  still wanted, which is also the retry payload. Listing the survivors would be
  data the client can derive, and derived data in a response is data that can
  disagree with itself.
- **Each entry carries its new `status`.** Enough for the UI to flip those seats
  in place and say why each one went, booked or held by someone else, without
  refetching the seat map. Same behaviour the single-seat conflict already had.
- **`seats-unavailable` replaces the earlier singular `seat-unavailable`**,
  which is retired. The request shape is changing in the same release, so a
  client that has not been updated is already broken; one break is better than a
  compatibility shim nobody will ever remove.

**422** validation failure: an empty `seatIds`, more than 6, a repeated seat,
seats in different rows, or a gap between them.
The detail names the cap when the cap is what failed.

**404** unknown showtime, or a seat id that does not exist in that showtime's
screen. Not a 409: a seat that was never there is a different mistake from a
seat that has gone.

---

## 4. `GET /api/holds/{holdId}`

Read a hold. Exists so the checkout page renders on a cold load rather than only
when the user arrives by clicking.

**200**

```json
{
  "holdId": "0199c3a4-7f21-7b8e-9d44-1a2b3c4d5e6f",
  "showtimeId": 1,
  "seats": [
    { "seatId": 1002, "rowLabel": "A", "seatNumber": 2, "tier": "NORMAL", "priceMinor": 1150 },
    { "seatId": 1061, "rowLabel": "F", "seatNumber": 1, "tier": "PREMIUM", "priceMinor": 1400 }
  ],
  "totalMinor": 2550,
  "currency": "GBP",
  "expiresAt": "2026-10-31T08:12:00Z",
  "ttlSeconds": 180,
  "status": "ACTIVE"
}
```

`status` is one of `ACTIVE`, `EXPIRED`, `CONVERTED`, and there is one of them
for the whole hold rather than one per seat. `ttlSeconds` is the remaining time,
so it shrinks across calls, and there is one deadline for every seat on the
hold.

The prices here are current, not the ones quoted when the hold was taken. They
only differ if a showtime's base price changes mid-hold, and a hold lasts five
minutes. The booking is where a price is frozen, because that is the record of
what was charged.

**404** unknown hold.

---

## 5. `POST /api/bookings`

Convert a hold into a booking.

**Request**

```json
{
  "holdId": "0199c3a4-7f21-7b8e-9d44-1a2b3c4d5e6f",
  "email": "mo@example.com"
}
```

`holdId` is the only thing identifying what is being booked. The hold already
knows its seats and its showtime, so re-sending them would let a client submit
a combination that disagrees with the server, and the server would have to
decide which to trust.

`email` is optional and exists for guests, who otherwise have nothing but the
reference. It is stored so a confirmation can be sent and is never used to
decide who a booking belongs to, for the reason in section 13. A signed-in
booking is found by its account instead, and the account comes from the session
cookie rather than the body, because a client-supplied user id is a
client-supplied claim about who it is.

**201**

```json
{
  "bookingReference": "SB-7F3K2Q",
  "showtimeId": 1,
  "filmTitle": "The Long Quiet",
  "screenName": "Screen 1",
  "startsAt": "2026-11-02T19:30:00Z",
  "seats": [
    { "rowLabel": "A", "seatNumber": 2, "tier": "NORMAL", "pricePaidMinor": 1150 },
    { "rowLabel": "F", "seatNumber": 1, "tier": "PREMIUM", "pricePaidMinor": 1400 }
  ],
  "totalPaidMinor": 2550,
  "currency": "GBP",
  "bookedAt": "2026-10-31T08:09:14Z"
}
```

One booking covers every seat on its hold. There is no converting part of a
hold, so this either writes the whole booking or none of it.

`pricePaidMinor` is per seat and records what was actually charged, so a later
price change never rewrites a receipt. `totalPaidMinor` is their sum and is not
stored anywhere: a stored total is a second place for the truth to live and a
chance for it to drift from the lines it summarises.

One currency for the booking, not per seat. One transaction, one currency.

### Retrying safely

`Idempotency-Key` is an optional request header. Without it this endpoint
behaves as it always did, so poking at the API by hand still works. With it, a
retry returns the original booking instead of a conflict, which is what a client
that never saw the first response actually needs.

| Case | Response |
|---|---|
| Same key, same body, first attempt finished | The original **201** and the same booking |
| Same key, same body, first attempt still running | **409** `problems/idempotency-key-in-progress` |
| Same key, different body | **422** `problems/idempotency-key-reused` |
| Same key, same hold, different account | **422** `problems/idempotency-key-reused` |

The key is claimed before the booking is attempted, not after. Recording it
afterwards means two concurrent retries both do the work; the primary key on the
claim is what serialises them. That is also why there is an in-progress case: a
retry arriving while the first attempt is mid-flight has no honest answer yet,
so it is told to ask again rather than given a guess.

"Same body" means the same `holdId`, the same account and the same email, which
is what the stored request hash covers. Hashing the hold alone would let the
same key sent with a different account take the replay path and hand back a
booking attributed to someone else, which is precisely the mistake reuse
detection exists to catch.

No response body is stored. A booking never changes, so re-reading it by
reference reproduces the original response exactly. A general idempotency layer
would store the body; this one leans on bookings being immutable, and says so.

Keys are kept for 24 hours, which covers any realistic retry.

**409** with a distinct `type` per case:

| `type` | Meaning |
|---|---|
| `problems/hold-expired` | The hold lapsed. The seats are available again. |
| `problems/hold-already-converted` | Double submit without an idempotency key. |
| `problems/idempotency-key-in-progress` | A retry arrived while the first attempt was still running. |

**422** `problems/idempotency-key-reused` if the key was already used for a
different hold. **404** unknown hold.

---

## 6. `GET /api/bookings/{bookingReference}`

Read a booking. Same body as above. **404** if unknown.

Exists so the confirmation page is reloadable and shareable.

The reference is a short human-readable code (`SB-7F3K2Q`), not the primary key.
People read references out loud; UUIDs do not survive that.

---

## 7. `DELETE /api/holds/{holdId}`

Release a hold before it expires.

**204** no content.

**404** unknown hold. **409** `problems/hold-already-converted` if it is already a
booking; a booking is cancelled, not released, and cancellation is out of scope.

Added after the design review: the checkout screen offers "Release and go back",
and without this the seat would sit locked for the rest of its five minutes even
though the user has explicitly walked away. Releasing early is the difference
between a hold that models intent and one that just models a timeout.

---

## 8. `POST /api/auth/signup`

Create an account. Signing up signs you in, because redirecting to a login form
you have just proved you can pass is friction for nothing.

**Request**

```json
{
  "email": "mo@example.com",
  "password": "correct horse battery",
  "displayName": "Mo"
}
```

`password` is 8 to 72 characters with no composition rules, no required symbol
or digit. Current NIST guidance is that those push people toward `Password1!`
and away from length, which is the thing that actually matters.

The 72 is not arbitrary. BCrypt ignores everything past 72 bytes, so a longer
password would have a silent dead tail and two passwords sharing a 72-byte
prefix would both unlock the account. Rejecting is honest; truncating is not.

**201**, with `Set-Cookie` for `sb_access` and `sb_refresh`

```json
{
  "id": "0199c3a4-7f21-7b8e-9d44-1a2b3c4d5e6f",
  "email": "mo@example.com",
  "displayName": "Mo",
  "createdAt": "2026-11-06T11:04:22Z"
}
```

No token in the body, here or anywhere else. A token the page can read is a
token an XSS bug can read. The body says who you are, the cookies carry the
credential.

**409** `problems/email-taken`. 409 rather than 422: the request is well formed,
the world disagrees with it.

**422** `problems/validation-failed` for a malformed address, a password outside
8 to 72, or a blank display name.

Addresses are stored and compared lowercase, so the case someone happened to
type at signup cannot lock them out later. `app_user.email` carries
`CHECK (email = lower(email))`, which makes that a guarantee rather than a
convention: any code path that forgets to normalise fails at the database
instead of quietly creating a second account for the same person.

---

## 9. `POST /api/auth/login`

**Request**

```json
{ "email": "mo@example.com", "password": "correct horse battery" }
```

No `@Email` and no `@Size` on this request, deliberately. Validating the shape
of a submitted credential tells an attacker which guesses were worth checking,
and a password that no longer meets today's rules must still be able to log in.

**200**, same body and the same two cookies as signup.

**401** `problems/invalid-credentials`.

One answer for "no such account" and for "wrong password". Two distinguishable
responses would let anyone confirm which addresses are registered by watching
status codes. The unknown-email path also runs a BCrypt comparison against a
dummy hash, so the two cases cannot be separated by timing either.

---

## 10. `POST /api/auth/refresh`

Exchange the refresh cookie for a new pair. No request body; reads `sb_refresh`.

**200**, same body as login, with both cookies replaced.

The user is returned as well as the cookies, so a client that has just started
up establishes who it is in one request rather than refreshing and then asking.

**401** `problems/invalid-refresh-token` for a token that is expired, unknown,
already spent, or absent. One answer for all four, so no case can be told from
another.

### Rotation and reuse detection

Every refresh invalidates the token it was called with and issues a new one.
Presenting a token that has already been spent means a copy exists somewhere it
should not, so it is treated as a compromise and **every session for that user
is revoked**, including the one the thief is holding.

The consequence a client has to design around: two tabs refreshing at the same
moment both present the same cookie, the second trips reuse detection, and the
user is signed out everywhere. A client must funnel refreshes through a single
in-flight request rather than letting each tab refresh independently.

Tokens are stored as a SHA-256 hash, not BCrypt. The token is already
high-entropy random, so there is nothing for a slow hash to protect. BCrypt's
cost exists for low-entropy human passwords.

---

## 11. `POST /api/auth/logout`

No body. Reads `sb_refresh`, revokes it, clears both cookies.

**204** whether or not there was a session. Logging out when you are not logged
in is not an error, the same way releasing a lapsed hold is not.

---

## 12. `GET /api/me`

**200** the same body as signup. **401** with no valid session.

The profile comes from the database, not from the token. The access token
carries only `sub`, so a changed display name is correct immediately rather than
fifteen minutes later when the token expires. A token is a credential, not a
cache.

---

## 13. `GET /api/me/bookings`

Every booking belonging to the signed-in account, newest first.

**200** an array of the same objects as `GET /api/bookings/{reference}`, or an
empty array for an account that has never booked. **401** with no valid session.

**Matches on `user_id` only, never on email.** `booking.email` is whatever was
typed at checkout and nobody has proved they own it, so matching on it would
hand a stranger's booking history to anyone willing to sign up with their
address.

That is worth stating plainly because `V10__users.sql` says the opposite. Its
comments describe `booking.email` as what "lets their old bookings surface if
they sign up with the same address later", and it creates a `booking_by_email`
index for exactly that. Both are right at different points: claiming guest
bookings by address is a real feature, and it is only safe once the address is
proven. There is no email verification yet, so this query matches on `user_id`
until there is, and the index waits unused.

Ordered by `booked_at DESC`, which is the order `booking_by_user (user_id,
booked_at DESC)` is already in, so the sort costs nothing. The index is partial,
`WHERE user_id IS NOT NULL`, and the query's `WHERE b.user_id = ?` implies that
predicate, which is what keeps it usable.

---

## Sessions and cookies

| Cookie | Path | Lifetime | Contents |
|---|---|---|---|
| `sb_access` | `/api` | 15 minutes | A JWT whose only claim is `sub` |
| `sb_refresh` | `/api/auth` | 30 days | An opaque random token |
| `XSRF-TOKEN` | `/` | session | The CSRF token, readable by the page |

Both session cookies are `HttpOnly` and `SameSite=Lax`, and `Secure` everywhere
except local plain http, where a `Secure` cookie is simply never set and login
would fail with nothing useful in the log.

`sb_refresh` is scoped to the one endpoint that consumes it. A refresh token
sent with every seat map request is a refresh token exposed to every bug in
every other handler; this one is only on the wire when it is being exchanged.

The access token is short because it is never checked against the database.
Revocation happens at refresh time, so fifteen minutes is the longest a revoked
session stays alive. The refresh token is long because it is checked every time
and can be deleted.

Both sides are served from one origin: Next.js rewrites `/api/*` to this
service, so the session cookie is first-party. Safari and Chrome both block
third-party cookies, so a cookie set directly by the API's own hostname would be
dropped on the frontend's page. That rewrite is also why CORS does not enter
into the frontend's path at all.

The token is read from the cookie by a custom `BearerTokenResolver` rather than
from an `Authorization` header. Returning no token leaves the request anonymous,
which is what makes guest checkout work: signature checking, expiry and the
security context stay the framework's job instead of a hand-rolled filter.

### CSRF

`POST /api/auth/refresh`, `POST /api/auth/logout` and any unsafe method under
`/api/me` require the `XSRF-TOKEN` cookie's value echoed back in an
`X-XSRF-TOKEN` header. Safe methods never need it, so `GET /api/me/bookings`
does not.

The CSRF cookie is deliberately readable by JavaScript. That is not a weakness:
a CSRF token is not a secret from the page, only from other origins, which the
same-origin policy already enforces.

The booking endpoints are exempt. They behave identically with or without a
session, so there is nothing an attacker could forge that they could not simply
do themselves, and leaving them open keeps Swagger UI and `curl` usable against
a public demo API. `signup` and `login` are exempt because the credentials are
in the body, so there is no ambient authority to forge; the residual risk is
login CSRF, forcing a victim into the attacker's account, which is real but
minor on a service holding nothing worth planting.

---

## Accounts and the booking flow

Accounts change three things and nothing else.

1. **The seat cap.** 3 seats per hold as a guest, 6 signed in.
2. **Attribution.** A booking confirmed while signed in records `user_id` and
   appears in section 13.
3. **Nothing else.** Every endpoint in sections 1 to 7 behaves exactly as it did
   without a session. Guest checkout is not a fallback path, it is the default
   one.

**Ownership is decided at confirm time, not when the hold is taken.** A guest
can pick seats, sign in on the checkout screen, and the booking is theirs. Who
you are when you commit is what matters, not who you were while browsing.

---

## Identifier strategy

| Resource | ID type | Why |
|---|---|---|
| Showtime, Seat | `bigint` | Catalogue data, safe to expose, cheap to index. |
| Hold, Booking | UUIDv7 | A hold ID is a capability: whoever has it can convert it to a booking, so it must be unguessable. UUIDv7 is time-ordered, so the index does not fragment the way random UUIDv4 does. |
| Booking reference | `SB-` + 6 base32 chars | Human-facing, readable aloud, distinct from the primary key. |

## Deliberately out of scope

Deferred, with the reasoning recorded so the omissions read as decisions rather
than gaps:

- **Idempotency keys on `POST /api/holds`.** Bookings have them, holds do not. A
  duplicated hold costs the user nothing: the second request either returns a
  409 or claims seats that lapse in five minutes. A duplicated booking is a
  seat somebody paid for twice.
- **Password reset.** Accounts can be created and signed into, but a forgotten
  password cannot be recovered. It needs an email provider, which would be the
  only third party this API depends on.
- **Email verification.** Nothing proves an address belongs to whoever typed
  it, which is why section 13 matches on `user_id` and `booking_by_email` goes
  unused.
- **Roles.** Every account is the same kind of account. There is no staff or
  admin view, so there is no authorisation model, only authentication.
- **Payment.** `pricePaidMinor` records the price at booking time; no money moves.
- **Pagination** on showtimes.

## Revisited decisions

Kept rather than erased, because why something was deferred and then added is
more informative than either half on its own.

- **Multi-seat holds.** Deferred on the grounds that the single-seat case
  already demonstrated the locking. Added once that was true. Locking one row
  proves the version check; locking several proves the ordering, which is the
  harder and more interesting half of the problem. The single-seat work was the
  right first scope precisely because it made this one tractable.
- **Idempotency keys on bookings.** Deferred as mattering only under real retry
  pressure. That was wrong about where the pressure comes from: it is not load,
  it is one person on a bad connection pressing Confirm twice. Cheap to add and
  the failure it prevents is the worst one in the system.
- **Authentication.** Out of scope through waves 1 and 2, on the grounds that a
  hold id is already a bearer capability and accounts are orthogonal to the
  concurrency problem. That reasoning held: nothing about the locking changed
  when accounts arrived. What it missed is that "who booked this" is the first
  question anyone asks of a booking system, and the answer being "nobody knows"
  made the rest look unfinished. The concurrency story did not need it. The
  artefact did.
