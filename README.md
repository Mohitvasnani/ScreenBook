# ScreenBook

[![CI](https://github.com/Mohitvasnani/ScreenBook/actions/workflows/ci.yml/badge.svg)](https://github.com/Mohitvasnani/ScreenBook/actions/workflows/ci.yml)

A cinema seat booking service built around one problem: when two people claim
the same seat at the same moment, exactly one must win.

The domain is the vehicle. The engineering is the point.

**[Live demo](https://screenbook.vercel.app)** · **[API](https://cinebook-67pa.onrender.com/api/showtimes)** · **[Swagger UI](https://cinebook-67pa.onrender.com/swagger-ui.html)**

> The API runs on a free tier that sleeps after 15 minutes of inactivity. The
> first request wakes it and takes a few seconds. Everything after that is
> normal.

---

## The race

[`/race`](https://screenbook.vercel.app/race) fires two hold requests at the
same seat at the same instant and shows both outcomes side by side. One reads
`201 WON`, the other `409 LOST`. Every time.

The test that proves it releases eight threads from a `CyclicBarrier` onto one
seat, against a real PostgreSQL started by Testcontainers:

| | Winners | Rows in `seat_hold` |
|---|---|---|
| With the row lock | **1** | 1 |
| With the lock removed | **8** | 8 |

That second row is the point. A concurrency test that passes proves very
little, because threads that never overlap pass too. Deleting the lock and
watching the same test fail is what makes it evidence.

```mermaid
sequenceDiagram
    participant A as Request A
    participant B as Request B
    participant DB as PostgreSQL

    A->>DB: BEGIN
    B->>DB: BEGIN
    A->>DB: SELECT seat ... FOR NO KEY UPDATE
    Note over A,DB: A holds the row lock
    B->>DB: SELECT seat ... FOR NO KEY UPDATE
    Note over B,DB: B blocks here
    A->>DB: status of seat -> AVAILABLE
    A->>DB: INSERT seat_hold
    A->>DB: COMMIT
    Note over B,DB: lock released, B resumes
    B->>DB: status of seat -> HELD
    B-->>B: 409 seat-unavailable
```

Without the lock, both transactions read `AVAILABLE` before either inserts.
Neither read is wrong at the moment it happens: PostgreSQL defaults to `READ
COMMITTED`, where an uncommitted insert by someone else is invisible. That is
write skew, and no amount of careful application code fixes it, because the
check and the write are not atomic with respect to each other.

## Three mechanisms, each where it fits

| Where | Mechanism | Why that one |
|---|---|---|
| Claiming a seat | Row lock, `SELECT ... FOR NO KEY UPDATE` | Contention on one seat is real but lasts under a millisecond. A lock is the cheapest thing that makes the check and the insert atomic. |
| Confirming a hold | Optimistic, `@Version` on `seat_hold` | A double submit is rare, and two confirmations genuinely are two updates to one row, which is the shape optimistic locking is for. |
| Releasing a hold | Conditional `UPDATE ... WHERE booked_at IS NULL` | Check and write in one statement. The loser gets zero rows rather than an exception, because "already released" is not an error for a `DELETE`. |
| Claiming several seats | The same row lock, taken in ascending seat id order | Two requests for the same seats in opposite orders would each hold one and wait for the other. A global order makes that cycle unformable. |
| Retrying a booking | `Idempotency-Key`, claimed before the work | A client that never saw the first response needs the original booking back, not a conflict. Recording the key afterwards would let two concurrent retries both do the work. |
| The invariant underneath | `UNIQUE (showtime_id, seat_id)` on `booking_seat` | The one rule that must hold even if every line of the application is wrong. |

`FOR NO KEY UPDATE` rather than `FOR UPDATE` is deliberate. Inserting a hold
takes a `FOR KEY SHARE` lock on its seat row for the foreign key check, and
`FOR UPDATE` would conflict with that, serialising holds on the same physical
seat across unrelated showtimes. `FOR NO KEY UPDATE` says "I will update this
row but not its key", so foreign key checks pass straight through.

## Seat status is derived, never stored

No column anywhere holds `HELD`. A seat is held only while an unconverted,
unreleased hold has `expires_at > now()`, evaluated per query by a view:

```sql
CREATE VIEW seat_availability AS
SELECT st.id AS showtime_id, s.id AS seat_id, s.row_label, s.seat_number,
       CASE
         WHEN EXISTS (SELECT 1 FROM booking_seat bs
                       WHERE bs.showtime_id = st.id AND bs.seat_id = s.id)
           THEN 'BOOKED'
         WHEN EXISTS (SELECT 1 FROM hold_seat hs
                       JOIN seat_hold h ON h.id = hs.hold_id
                      WHERE hs.showtime_id = st.id AND hs.seat_id = s.id
                        AND h.booked_at IS NULL AND h.released_at IS NULL
                        AND h.expires_at > now())
           THEN 'HELD'
         ELSE 'AVAILABLE'
       END AS status,
       s.tier,
       p.price_minor AS base_price_minor
FROM showtime st
JOIN screen    sc ON sc.id        = st.screen_id
JOIN seat      s  ON s.screen_id  = st.screen_id
JOIN seat_tier t  ON t.code       = s.tier
JOIN hall_class_tier_price p
                  ON p.hall_class = sc.hall_class
                 AND p.tier       = s.tier;
```

The listed price is resolved here too, from the hall class and the tier, so
the seat map and the booking path read the same number and a displayed price
cannot drift from a charged one. What moves that number is in
[What a seat costs](#what-a-seat-costs) below.

An expired hold frees its seat the instant it expires, with no sweeper
involved. A job that deletes old hold rows is then housekeeping, not
correctness, which is a much better thing to be able to say about a job.

```mermaid
stateDiagram-v2
    [*] --> AVAILABLE
    AVAILABLE --> HELD: POST /api/holds
    HELD --> AVAILABLE: expires_at passes
    HELD --> AVAILABLE: DELETE /api/holds/{id}
    HELD --> BOOKED: POST /api/bookings
    BOOKED --> [*]
```

`EXISTS` rather than a left join, because a left join returns one row per match
and would print a seat twice if two live holds ever existed. Correctness that
does not depend on a guarantee made somewhere else is worth two extra
characters.

## What a seat costs

Two halves, and the split is the point. The listed price is data a human
chose. The rules that move it are a function of live state, so they live in
Java where they can be tested without a database.

| | Normal | Premium | Executive |
|---|---|---|---|
| Standard hall | 200 | 350 | 500 |
| Luxury hall | 300 | 550 | 800 |

Listed, not base plus a global uplift. One uplift is a huge ladder in a cheap
hall and nearly flat in an expensive one, and percentage uplifts produce
prices like 437.50. Every figure above is one a human picked and would be
willing to print, which is what real cinemas publish.

Four rules move it, each contributing a percentage of the listed price:

| Rule | Bands |
|---|---|
| Occupancy | +10% from half full, +20% from 80% |
| Lead time | +5% inside 48 hours, +10% inside 6 |
| Time of day | +5% for an evening screening |
| Day of week | +5% on Friday and Saturday |

They are summed rather than multiplied, so every line of the breakdown is
independently true. Multiplied, the figure printed beside a rule would depend
on which rules happened to fire before it. A cap sits underneath at +40%,
which these four cannot reach between them; it exists so that a mistyped band
cannot produce a four-figure ticket.

`PricingRules` is a pure function of its arguments. `now` arrives as a
parameter rather than a `Clock` field, and there is no repository and no
transaction, which is why its tests are the only ones in the project that do
not need Docker. Evening and weekend are questions about Indian local time, so
the zone is configuration and never the server's default.

### A quote is frozen, a listing is not

The seat map quotes live prices and promises nothing. The promise is made when
a hold is taken, and at that moment the numbers are copied onto it: the listed
price on `seat_hold`, the adjusted price per seat on `hold_seat`, and one row
per rule on `hold_price_rule`. Confirming charges the copy.

That is not caution. Occupancy is an input, so recomputing at confirm returns
a different number whenever somebody else books while the customer is typing
their email. The rule labels are copied for the same reason the price is:
rewording a band next month must not retroactively change why last month's
booking cost what it did.

Occupancy is read inside the seat locks and before the insert, so the figure
that sets the price is the one that was true at the instant of the claim. Read
earlier, it prices against a house that has since filled. Read after the
insert, it counts the customer's own seats and lets them inflate their own
price.

`PriceFreezeTest` asserts that the number quoted is the number charged.

## Where the data comes from

The cinemas are real. 130 of them across 40 cities, 103 in India and 27 in the
UK, from OpenStreetMap via Nominatim. Names, coordinates and addresses are
genuine and every row carries its OSM node or way id in a comment, so any of
them can be checked. Coverage is uneven on purpose: six cinemas in Kolkata, one
in Chandigarh, none in Chennai. That is a real gap in open data, and filling it
with invented rows would hide the only interesting thing about using a real
source.

Everything else is generated and labelled as such. No chain publishes seat maps
or showtimes in a form anyone can use, so the 240 screens, 33,752 seats and all
the prices are invented. A varied room layout gives the identical concurrency
problem whether it was scraped or generated.

Cinema data © OpenStreetMap contributors, available under the
[Open Database License](https://www.openstreetmap.org/copyright).

### Screenings are seeded at startup, not by a migration

A migration runs once. Flyway records it as applied and never revisits it, so
data seeded by one is a photograph of the day it ran, and a demo seeded with a
week of screenings is empty eight days after deploy.

So the seeder does not seed a week. It states a target state:

> every screen has screenings for the next seven days, at times local to its
> cinema, and none in the past

and converges to it on every startup. Idempotency is not a feature added on
top, it falls out of describing a state rather than an action: the second run
finds the state already true and inserts nothing. Roughly 6,400 screenings
across 240 screens, created by one `INSERT ... SELECT` rather than 6,400 round
trips.

`AT TIME ZONE` reads `cinema.timezone`, a column, not a constant. A Jaipur
screening is scheduled on Jaipur's clock and a Liverpool one on Liverpool's,
which is why the zone lives on the cinema: a platform operating in two
countries does not have one evening. Films are assigned off a ring computed
with `row_number()`, so three films today and forty later run the same
statement.

Expired screenings are deleted as housekeeping, and only ones with no booking
and no hold against them. A screening somebody booked a seat at is their
record, not clutter.

## Architecture

```mermaid
flowchart LR
    U["Browser"]
    V["Next.js 16<br/>Vercel"]
    A["Spring Boot 4<br/>Render, Frankfurt"]
    N[("PostgreSQL 18<br/>Neon, Frankfurt")]

    U -->|"page load"| V
    U -->|"/api, CORS"| A
    A -->|"JDBC over TLS"| N
```

Writes go through JPA entities, because they need the unit of work and the
version. Reads go through explicit SQL returning records, because the response
shapes are joins across three and four tables that match no entity, and writing
the join is clearer than persuading an ORM to produce it.

Entities hold foreign keys as ids rather than as associations. A hold
references a seat by identity; it does not contain one. The practical effect is
that there is no lazy loading anywhere, so N+1 queries and
`LazyInitializationException` are not failure modes this application can have.

## API

Fifteen endpoints. Full contract in
[docs/api-contract.md](docs/api-contract.md).

| | |
|---|---|
| `GET /api/showtimes` | upcoming screenings with live seat counts |
| `GET /api/showtimes/{id}/seats` | the seat map, status computed per request |
| `POST /api/holds` | claim adjacent seats for 300 seconds, three as a guest and six signed in |
| `GET /api/holds/{id}` | read a hold and its remaining time |
| `DELETE /api/holds/{id}` | release early |
| `POST /api/bookings` | convert a hold into a booking |
| `GET /api/bookings/{reference}` | read a booking by its `SB-` code |
| `POST /api/auth/signup` | create an account, signed in on success |
| `POST /api/auth/login` | exchange credentials for session cookies |
| `POST /api/auth/refresh` | rotate the session, with reuse detection |
| `POST /api/auth/logout` | revoke the session and clear the cookies |
| `POST /api/auth/forgot-password` | start a reset, always `202`, tells the caller nothing |
| `POST /api/auth/reset-password` | set the password and end every session |
| `GET /api/me` | the signed-in account |
| `GET /api/me/bookings` | that account's bookings, newest first |

Errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem details
with a distinct `type` URI per case, so clients branch on a stable identifier
rather than on prose:

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

Only the lost seats are listed. The client knows what it asked for, so
requested minus unavailable is the set still wanted, and that is also the retry
payload. Anything derivable is left out, because derived data in a response is
data that can disagree with itself.

Catalogue rows use `bigint` ids. Holds and bookings use UUIDv7: a hold id is a
capability, so it has to be unguessable, and v7 is time ordered so the index
appends instead of fragmenting the way random v4 does. A booking reference is
`SB-` plus six characters from an alphabet with no `0`, `1`, `I`, `L`, `O` or
`U`, because people read references aloud. Money is integer minor units, never
a float.

## Running it

```bash
docker compose up -d                 # PostgreSQL 18 on 5432

cd backend && ./gradlew bootRun      # http://localhost:8082
cd frontend && npm install && npm run dev   # http://localhost:3000
```

Flyway applies the migrations on startup, so there is no separate migrate step.
The frontend runs standalone without a backend: `NEXT_PUBLIC_API_MODE=mock`
serves the whole flow from a Mock Service Worker in the browser. Environment
variables are documented in [docs/environment.md](docs/environment.md).

| | |
|---|---|
| API | http://localhost:8082/api |
| Swagger UI | http://localhost:8082/swagger-ui.html |
| Health | http://localhost:8082/actuator/health |

## Tests

```bash
cd backend && ./gradlew test
```

Integration tests run against a real PostgreSQL in a container, not an
in-memory substitute, because the behaviour under test is a PostgreSQL row
lock and an in-memory database would not have it. GitHub Actions runs the same
suite on every push.

## Deliberately out of scope

Deferred with the reasoning recorded, so the omissions read as decisions.

- **Email verification.** Nothing proves an address belongs to whoever typed
  it, which is why my-bookings matches on `user_id` and never on email.
- **Payment.** `pricePaidMinor` records the price at booking time. No money
  moves.
- **GraphQL.** Fifteen fixed-shape resources with no over-fetching problem, and
  an error model that depends on the HTTP status codes GraphQL discards.

## Reversed on purpose

Four things were deferred and then built, and why is more informative than
either half alone.

- **Multi-seat holds**, deferred because the single-seat case already
  demonstrated the locking. Added once that was true: locking one row proves
  the version check, locking several proves the ordering, which is the harder
  half. The first scope was right precisely because it made this one tractable.
- **Idempotency keys**, deferred as mattering only under real retry pressure.
  That was wrong about where the pressure comes from. It is not load, it is one
  person on a bad connection pressing Confirm twice.
- **Password reset**, deferred because it needs a mail provider and nothing
  else depended on it. Built once accounts existed, because an account you can
  be locked out of forever is not really an account. The provider sits behind
  a `Mailer` interface with a logging implementation, so the suite still needs
  nothing but Docker, and an unknown address, a rate-limited one and a real
  one are indistinguishable from outside.
- **Authentication**, deferred on the grounds that a hold id is already a
  bearer capability and accounts are orthogonal to the concurrency problem.
  That reasoning held: nothing about the locking changed when accounts
  arrived. What it missed is that "who booked this" is the first question
  anyone asks of a booking system, and the answer being "nobody knows" made
  the rest look unfinished. The concurrency story did not need it. The
  artefact did.

## Next

- **Cinema endpoints and a map.** The schema and the data are in: screens
  belong to a cinema, cinemas carry a `geography(Point, 4326)` and a GiST
  index, so the nearest few come back from one indexed query rather than a
  widening loop. What is left is exposing them and drawing them.
- **Real film data** from TMDB, ingested once offline and committed as SQL, so
  the running service keeps exactly one third-party dependency. It also fills
  `film.released_on`, which is what makes a weeks-since-release pricing rule
  testable rather than an opinion.
- **Email verification**, which is what makes claiming a guest booking by its
  address safe. The `booking_by_email` index already exists for it and goes
  deliberately unused until an address can be proven.
- **An exclusion constraint** on `hold_seat`, moving "at most one live hold per
  seat" from a locking convention into the schema, where no future code path
  can bypass it. `released_at` already exists for its predicate.
- **Hexagonal refactor** with ArchUnit enforcing the module boundaries.
- **Redis** for distributed locking once the service is more than one process.
- **Prometheus and Grafana**, and a load test with numbers.
- **Kubernetes and Helm** on Oracle Cloud.

## Stack

| | |
|---|---|
| Backend | Java 21, Spring Boot 4.1.1, Spring Security 7, Hibernate 7, Gradle 9.7 |
| Database | PostgreSQL 18 with PostGIS 3.6, Flyway 12, HikariCP |
| Frontend | Next.js 16.4, React 19, TypeScript, CSS Modules, MSW |
| Testing | JUnit 5, Testcontainers |
| Docs | springdoc-openapi, Swagger UI |
| Mail | Brevo, behind a `Mailer` interface with a logging implementation |
| Infrastructure | Docker multi-stage, GitHub Actions, Render, Neon, Vercel |
