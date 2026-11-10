# ScreenBook API Contract

The document both sides are built against. Written before either side was
implemented, so the Next.js client and the Spring Boot service agree by
construction rather than by discovery.

- Base path: `/api`
- All timestamps ISO-8601 UTC with a trailing `Z`
- All money in integer minor units, never floats, with 100 to the major unit.
  Every amount carries its own `currency`, taken from the cinema: paise at an
  Indian cinema, pence at a UK one. Nothing converts between them anywhere
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
- Prices move with demand. The showtimes list and the seat map quote live
  prices; a hold freezes its price and the booking charges that frozen figure

Nineteen endpoints. Three are cinemas, added in wave 5, which is where a
customer starts: the map, the nearest few, and one building. Eight are the
booking flow: four doing the work, three `GET`s that exist
so the client's URLs survive a page refresh, one releasing a hold early. The other eight are accounts: six added in wave 3, and password reset in
wave 4.

---

## Pricing

Every price in this API is a listed price moved by rules. Both halves are
server side; the client never multiplies anything.

### The listed price

A hand-set table, one row per hall class and seat tier. Nothing computes these.

| Hall class | Normal | Premium | Executive |
|---|---|---|---|
| Standard (Screens 1 to 3) | 20000 | 35000 | 50000 |
| Luxury (Screen 4) | 30000 | 55000 | 80000 |

Deliberately not base-plus-uplift. One global uplift means a fixed gap is a
steep ladder in a 200 rupee hall and nearly flat in a 400 rupee one, and
percentage uplifts fix the ratio at the cost of prices like 437.50. Listing
them is what cinemas actually publish, and every figure is a number a human
picked. Both ladders are about 2.5 times bottom to top, which is the spread a
Jaipur single screen runs across its named seat classes.

Every figure is a multiple of 50 rupees, which is why whole-percent rules below
never produce a fraction of a rupee.

### The rules

Four of them, each a percentage of the seat's listed price, expressed in basis
points. 10000 basis points is 100%.

| Rule | Band | Adjustment |
|---|---|---|
| `OCCUPANCY` | 80% or more of the house taken | +2000 |
| | 50% to 79% | +1000 |
| | under 50% | 0 |
| `LEAD_TIME` | under 6 hours to the screening | +1000 |
| | under 48 hours | +500 |
| | 48 hours or more | 0 |
| `TIME_OF_DAY` | starts 17:00 to 22:59 local | +500 |
| | otherwise | 0 |
| `DAY_OF_WEEK` | Friday or Saturday local | +500 |
| | otherwise | 0 |

**They add up, they do not multiply.** +2000, +1000, +500 and +500 means +4000
basis points of the listed price, not a product of four factors. Each line of a
breakdown is then independently true; multiplied, the figure beside a rule
would depend on which rules fired before it, so a breakdown would only be
readable in one particular order.

**Each line is rounded on its own**, half away from zero, and then summed.
Rounding only the total would leave the lines not adding up to the figure
printed above them.

**Occupancy counts booked and live-held seats**, measured before the request's
own seats are written, so nobody can inflate their own price. It is read from
the same rows the seat map renders, so the number that sets the price cannot
disagree with what a customer is looking at.

**Local means `Asia/Kolkata`**, from configuration. `startsAt` is an instant
and "Friday evening" is a local fact, so without an explicit zone a server
running UTC would price a screening differently from a laptop.

**The total is capped at +4000 basis points.** The four rules sum to exactly
that, so the cap cannot fire as configured. It exists so a mistyped band cannot
produce a four-figure ticket, and because a legal ceiling has to be
expressible: several Indian states cap cinema ticket prices by statute. If it
ever fires it appears as its own `CAP` line with a negative adjustment, rather
than silently shrinking the total, so a breakdown always sums to the price
charged.

Worst case on a Standard Normal seat, a full house on a Friday evening three
hours before the screening: 20000 becomes 28000.

### Live versus frozen

The showtimes list and the seat map quote **live** prices. They move as the
house fills and the screening approaches, and nothing is promised.

`POST /api/holds` **freezes** the price. The figure quoted in that response,
and the breakdown explaining it, are stored on the hold and are what
`POST /api/bookings` charges. Confirm never recomputes a price.

That is the whole reason the freeze exists. Once occupancy is an input, reading
the price a second time gives a different answer: five seats booked by somebody
else while a customer types their email can cross an occupancy band. Without
the freeze they would be charged a number they were never shown.

---

## 1. `GET /api/cinemas`

Every cinema, for the map and for the city picker to group.

**200**

```json
[
  {
    "id": 42,
    "name": "Everyman",
    "city": "Liverpool",
    "state": "England",
    "latitude": 53.407184,
    "longitude": -2.98644,
    "currency": "GBP",
    "screenCount": 3
  }
]
```

No pagination and no `city` parameter. 130 rows is one response, and the client
already holds all of them to draw markers, so a city filter would be a second
round trip for what a local `filter()` does.

`latitude` and `longitude` as two numbers rather than GeoJSON. A marker needs
two numbers, and MapLibre takes them as `[lon, lat]`, so a `Point` wrapper would
be a shape every client unwraps for nothing.

No `address`. 130 addresses is most of this payload's weight and a marker does
not show one. Section 3 has it.

`screenCount` is the cinema's own screens, counted over a left join, so a cinema
with none still appears on the map as a building that exists.

---

## 2. `GET /api/cinemas/nearest`

The nearest few to a point. This is what makes 130 buildings usable from
wherever the customer is standing.

| | | |
|---|---|---|
| `lat` | required | `-90` to `90` |
| `lon` | required | `-180` to `180` |
| `limit` | optional | defaults to 5, clamped to 20 |

**200**

```json
[
  {
    "id": 42,
    "name": "Everyman",
    "city": "Liverpool",
    "state": "England",
    "latitude": 53.407184,
    "longitude": -2.98644,
    "currency": "GBP",
    "screenCount": 3,
    "distanceMetres": 718
  }
]
```

`ORDER BY location <-> point LIMIT n` against the GiST index, so this walks the
index nearest-first and stops, rather than computing 130 distances and sorting
them. At 130 rows that saves nothing; the index is there for the shape of the
query.

The ordering and the reported distance use the same sphere model. `ST_Distance`
defaults to the spheroid and the `<->` operator does not, and mixing them lets a
list show 2,411m above 2,409m. The sphere is out by around 0.3 percent, which at
this range is a few metres.

`distanceMetres` is an integer. `ST_Distance` returns a double and sub-metre
precision on the position of a building is noise, so it is rounded once, here,
rather than by every client.

No radius parameter. "The nearest five" is always answerable and a radius is
not: 50km finds four cinemas from Liverpool and none from a village, and an
empty list is a worse answer than a long drive. The distance is reported and
the customer judges.

A `limit` above the cap is clamped rather than rejected. A client that does not
know the cap should get an answer, not an error it has to guess its way out of.

**422** `validation-failed` for a latitude or longitude outside its range, or a
`limit` below 1. A missing or unparseable `lat` or `lon` is a **400** from
Spring's own parameter binding, in the same problem+json shape.

---

## 3. `GET /api/cinemas/{cinemaId}`

One cinema.

**200**

```json
{
  "id": 42,
  "name": "Everyman",
  "city": "Liverpool",
  "state": "England",
  "address": "Whitechapel, Cavern Quarter, L1 6DA",
  "latitude": 53.407184,
  "longitude": -2.98644,
  "currency": "GBP",
  "timezone": "Europe/London",
  "screenCount": 3
}
```

`timezone` is sent because showtimes are formatted in the cinema's local time,
not the browser's. Someone in Liverpool looking at Jaipur should see 20:00,
which is when the film starts for the people in the room, and `startsAt` is an
instant so the client cannot work that out alone.

No list of screens. A customer picks a showtime, not a screen, and the showtimes
list already carries `screenName`.

**404** `not-found` for an unknown id.

---

## 4. `GET /api/showtimes`

What is on at one cinema.

| | | |
|---|---|---|
| `cinemaId` | required | the cinema whose screenings to list |

**200**

```json
[
  {
    "id": 1,
    "cinemaId": 7,
    "filmTitle": "The Long Quiet",
    "screenName": "Screen 1",
    "startsAt": "2026-11-02T19:30:00Z",
    "runtimeMinutes": 128,
    "basePriceMinor": 20000,
    "priceMinor": 28000,
    "currency": "INR",
    "seatsAvailable": 87,
    "seatsTotal": 120
  }
]
```

`cinemaId` is required and has no default. There is no sensible one. The 130
cinemas have 6,366 upcoming screenings between them, so the twelve soonest on
earth are four arbitrary buildings in two countries and nobody's listing. "What
is on near me" is answered by choosing a cinema first, which is what sections 1
and 2 are for.

Three different answers, kept distinct. A cinema that exists with nothing
scheduled is an empty list. An id no cinema has is a **404** `not-found`. A
missing `cinemaId` is a **400** from Spring's parameter binding.

No pagination. The dataset is bounded by what one cinema screens in a week, and
the response is capped at `screenbook.showtimes.max-results`.

Only screenings that have not started are listed, on `starts_at > now()`. A
screening disappears from this list and stops being claimable at the same
instant.

`priceMinor` is the cheapest seat **actually on sale** for the screening, which
is the Normal tier after the rules. `basePriceMinor` is what that seat is
listed at. Both are sent so a list can say "higher than usual" without doing
arithmetic, and so the "from" figure is one somebody can really buy at: a
headline price nobody can reach is the practice the CMA's pricing guidance is
about.

No rule breakdown here. Each screening has its own rules firing, so twelve rows
would carry up to forty-eight explanation lines nobody reads, and two prices
side by side already say what a list needs to say. The reasons live on the seat
map and the checkout.

`seatsAvailable` counts seats, not bookings. It is derived from
`showtime_occupancy`, the same view the pricing rules read.

`cinemaId` is sent even though the caller supplied it. The caller of this
endpoint does not need it; whoever follows a link to one of these rows does.
Section 5 and the seat map are both reached by URL with a showtime id and
nothing else, so without this field a page has no route back to the building.

---

## 5. `GET /api/showtimes/{showtimeId}`

One screening.

**200** The same object as one row of section 4.

Exists because section 4 is scoped to a cinema and two screens are not. The
seat map and the checkout are reached by URL carrying a showtime id and nothing
else, so neither can ask for a listing to find their screening in. Before this
they listed every screening and filtered client-side, which stopped being
possible the moment a listing needed a cinema.

No `starts_at` filter, unlike section 4, and that is the difference between the
two. A listing shows what can still be booked. This answers what a screening
is, so a confirmation page or a bookmarked seat map opened after the film
started still renders. Whether it can be booked is section 7's answer, and that
rejects a started showtime with a **409**.

Prices here are live, exactly as in the listing. A price is only promised once a
hold exists.

**404** `not-found` for an unknown id.

---

## 6. `GET /api/showtimes/{showtimeId}/seats`

The seat map for one showtime.

**200**

```json
{
  "showtimeId": 1,
  "seats": [
    { "id": 1001, "rowLabel": "A", "seatNumber": 1, "status": "AVAILABLE",
      "tier": "NORMAL", "tierName": "Normal",
      "basePriceMinor": 20000, "priceMinor": 28000, "currency": "INR" },
    { "id": 1061, "rowLabel": "F", "seatNumber": 1, "status": "HELD",
      "tier": "PREMIUM", "tierName": "Premium",
      "basePriceMinor": 35000, "priceMinor": 49000, "currency": "INR" },
    { "id": 1097, "rowLabel": "I", "seatNumber": 1, "status": "BOOKED",
      "tier": "EXECUTIVE", "tierName": "Executive",
      "basePriceMinor": 50000, "priceMinor": 70000, "currency": "INR" }
  ],
  "priceRules": [
    { "code": "OCCUPANCY",   "label": "Nearly full",  "basisPoints": 2000 },
    { "code": "LEAD_TIME",   "label": "Under 6 hours to showtime", "basisPoints": 1000 },
    { "code": "TIME_OF_DAY", "label": "Evening show", "basisPoints": 500 },
    { "code": "DAY_OF_WEEK", "label": "Weekend",      "basisPoints": 500 }
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

`basePriceMinor` is the seat's listed price for its hall class and tier.
`priceMinor` is what it costs right now, after the rules. They are equal when
no rule is firing. See [Pricing](#pricing) for the table and the bands.

Neither is frozen. Both move as the house fills and the screening approaches; a
price is only promised once a hold exists.

`priceRules` lists only the rules currently moving prices, and as percentages
with no money attached. The map spans all three tiers, and each rule is a
percentage of each tier's own listed price, so one shared money figure would be
wrong for two of the three. The percentages are identical across tiers, so they
are reported once and every seat carries its own price. The checkout gets the
money version, where a hold is always one tier by construction.

An empty `priceRules` is the ordinary case and means every seat is at its
listed price. Render it as a single line, not an empty panel.

`status`, `tier` and `basePriceMinor` all come from one view,
`seat_availability`, so the seat map and the claim path cannot disagree about a
seat:

```sql
CREATE VIEW seat_availability AS
SELECT st.id         AS showtime_id,
       s.id          AS seat_id,
       s.row_label   AS row_label,
       s.seat_number AS seat_number,
       CASE
         WHEN EXISTS (SELECT 1 FROM booking_seat bs
                       WHERE bs.showtime_id = st.id
                         AND bs.seat_id     = s.id)
           THEN 'BOOKED'
         WHEN EXISTS (SELECT 1 FROM hold_seat hs
                       JOIN seat_hold h ON h.id = hs.hold_id
                      WHERE hs.showtime_id = st.id
                        AND hs.seat_id     = s.id
                        AND h.booked_at    IS NULL
                        AND h.released_at  IS NULL
                        AND h.expires_at   > now())
           THEN 'HELD'
         ELSE 'AVAILABLE'
       END           AS status,
       s.tier          AS tier,
       t.display_name  AS tier_name,
       sc.hall_class   AS hall_class,
       p.price_minor   AS base_price_minor,
       st.currency     AS currency
FROM showtime st
JOIN screen    sc ON sc.id        = st.screen_id
JOIN seat      s  ON s.screen_id  = st.screen_id
JOIN seat_tier t  ON t.code       = s.tier
JOIN hall_class_tier_price p
                  ON p.hall_class = sc.hall_class
                 AND p.tier       = s.tier;
```

`status` is computed per request, never stored on a seat. The
`h.expires_at > now()` predicate is doing the real work: an expired hold reads
as AVAILABLE the instant it expires, with no sweeper involved. A scheduled job
to delete old hold rows is then housekeeping, not correctness.

The joins to `seat_tier` and `hall_class_tier_price` are inner joins, so a hall
class missing a price for one of its tiers does not error, it makes those seats
vanish from the seat map. A test asserts every combination has a row, because
that failure is silent in production and loud at build time.

`showtime_occupancy` sits on top of the same two child tables and answers "how
full is this screening" for the pricing rules and for `seatsAvailable`. A test
asserts the two agree, since one counts with correlated subqueries for speed
and the other with a `CASE` per seat.

---

## 7. `POST /api/holds`

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
    { "seatId": 1002, "rowLabel": "A", "seatNumber": 2, "tier": "NORMAL", "priceMinor": 28000 },
    { "seatId": 1003, "rowLabel": "A", "seatNumber": 3, "tier": "NORMAL", "priceMinor": 28000 }
  ],
  "totalMinor": 56000,
  "currency": "INR",
  "pricing": {
    "baseMinor": 20000,
    "adjustedMinor": 28000,
    "rules": [
      { "code": "OCCUPANCY",   "label": "Nearly full",
        "basisPoints": 2000, "adjustmentMinor": 4000 },
      { "code": "LEAD_TIME",   "label": "Under 6 hours to showtime",
        "basisPoints": 1000, "adjustmentMinor": 2000 },
      { "code": "TIME_OF_DAY", "label": "Evening show",
        "basisPoints": 500,  "adjustmentMinor": 1000 },
      { "code": "DAY_OF_WEEK", "label": "Weekend",
        "basisPoints": 500,  "adjustmentMinor": 1000 }
    ]
  },
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

**Every price in this response is frozen.** It is the figure computed at the
moment of the claim, and `POST /api/bookings` charges it rather than working
the price out again. This is what lets a checkout screen promise the price is
held.

`pricing` explains how one seat reached its price: the listed figure, the
adjusted figure, and one line per rule that moved it, in display order.
`baseMinor` plus every `adjustmentMinor` equals `adjustedMinor`, always,
including the `CAP` line if it ever appears.

A hold covers exactly one tier, so one `pricing` block describes every seat on
it. That is not a rule anyone wrote down: seats on a hold must share a row, and
tier is assigned by row band, so it falls out of two unrelated rules. The price
is still recorded per seat in the database, so the schema stays correct if
either of those changes.

Only the rules that moved the price appear. The bands that did not fire are
stored, so a hold taken before a rule existed stays distinguishable from one
where the rule simply did not fire, but a customer does not need to read
"Occupancy, plus nothing". An empty `rules` means the listed price stands.

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

**409** the screening has already started:

```json
{
  "type": "https://screenbook.dev/problems/showtime-started",
  "title": "Showtime started",
  "status": 409,
  "detail": "That screening has already started and can no longer be booked.",
  "instance": "/api/holds"
}
```

409 rather than 422, because the request was valid when the page was loaded and
the world moved on. Same family as `seats-unavailable`: the client should
refresh rather than correct anything. The boundary matches the showtimes list,
so a screening stops being listed and stops being claimable at the same
instant.

Deliberately not applied to `POST /api/bookings`. A hold taken legitimately
four minutes before the start can still be confirmed inside its five minute
TTL, and blocking that would strand a customer who did nothing wrong. The TTL
governs that window, not the start time.

**422** validation failure: an empty `seatIds`, more than 6, a repeated seat,
seats in different rows, or a gap between them.
The detail names the cap when the cap is what failed.

**404** unknown showtime, or a seat id that does not exist in that showtime's
screen. Not a 409: a seat that was never there is a different mistake from a
seat that has gone.

---

## 8. `GET /api/holds/{holdId}`

Read a hold. Exists so the checkout page renders on a cold load rather than only
when the user arrives by clicking.

**200**

```json
{
  "holdId": "0199c3a4-7f21-7b8e-9d44-1a2b3c4d5e6f",
  "showtimeId": 1,
  "seats": [
    { "seatId": 1002, "rowLabel": "A", "seatNumber": 2, "tier": "NORMAL", "priceMinor": 28000 },
    { "seatId": 1003, "rowLabel": "A", "seatNumber": 3, "tier": "NORMAL", "priceMinor": 28000 }
  ],
  "totalMinor": 56000,
  "currency": "INR",
  "pricing": {
    "baseMinor": 20000,
    "adjustedMinor": 28000,
    "rules": [
      { "code": "OCCUPANCY",   "label": "Nearly full",
        "basisPoints": 2000, "adjustmentMinor": 4000 },
      { "code": "TIME_OF_DAY", "label": "Evening show",
        "basisPoints": 500,  "adjustmentMinor": 1000 }
    ]
  },
  "expiresAt": "2026-10-31T08:12:00Z",
  "ttlSeconds": 180,
  "status": "ACTIVE"
}
```

`status` is one of `ACTIVE`, `EXPIRED`, `CONVERTED`, and there is one of them
for the whole hold rather than one per seat. `ttlSeconds` is the remaining time,
so it shrinks across calls, and there is one deadline for every seat on the
hold.

The prices and the breakdown are the ones frozen when the hold was taken, not
today's. A checkout screen reloaded four minutes in shows the same figures as
the original quote even if the house has filled since, which is the whole point
of freezing them.

The seats in this example are the two in the row above, so this is the same
hold read back rather than a different one: one `pricing` block, because a hold
is one tier.

**404** unknown hold.

---

## 9. `POST /api/bookings`

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
decide who a booking belongs to, for the reason in section 19. A signed-in
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
    { "rowLabel": "A", "seatNumber": 2, "tier": "NORMAL", "pricePaidMinor": 28000 },
    { "rowLabel": "A", "seatNumber": 3, "tier": "NORMAL", "pricePaidMinor": 28000 }
  ],
  "totalPaidMinor": 56000,
  "currency": "INR",
  "bookedAt": "2026-10-31T08:09:14Z"
}
```

One booking covers every seat on its hold. There is no converting part of a
hold, so this either writes the whole booking or none of it.

`pricePaidMinor` is per seat and records what was actually charged, so a later
price change never rewrites a receipt. `totalPaidMinor` is their sum and is not
stored anywhere: a stored total is a second place for the truth to live and a
chance for it to drift from the lines it summarises.

These are the figures frozen on the hold, copied across unchanged. This
endpoint never recomputes a price, which is what makes the quoted price and the
charged price the same number by construction rather than by coincidence. A
test edits the listed price between hold and confirm and asserts the booking
still charges what was quoted.

No breakdown on the booking. `booking.hold_id` is `NOT NULL UNIQUE`, so the
join back to the hold that explains the price is total and exact, and copying
four immutable rows to a second table would buy nothing. The one cost is that
purging old holds would strip the explanation off old receipts, and purging is
not in scope.

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

## 10. `GET /api/bookings/{bookingReference}`

Read a booking. Same body as above. **404** if unknown.

Exists so the confirmation page is reloadable and shareable.

The reference is a short human-readable code (`SB-7F3K2Q`), not the primary key.
People read references out loud; UUIDs do not survive that.

---

## 11. `DELETE /api/holds/{holdId}`

Release a hold before it expires.

**204** no content.

**404** unknown hold. **409** `problems/hold-already-converted` if it is already a
booking; a booking is cancelled, not released, and cancellation is out of scope.

Added after the design review: the checkout screen offers "Release and go back",
and without this the seat would sit locked for the rest of its five minutes even
though the user has explicitly walked away. Releasing early is the difference
between a hold that models intent and one that just models a timeout.

---

## 12. `POST /api/auth/signup`

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

## 13. `POST /api/auth/login`

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

## 14. `POST /api/auth/refresh`

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

## 15. `POST /api/auth/logout`

No body. Reads `sb_refresh`, revokes it, clears both cookies.

**204** whether or not there was a session. Logging out when you are not logged
in is not an error, the same way releasing a lapsed hold is not.

---

## 16. `POST /api/auth/forgot-password`

Starts a password reset.

**Request**

```json
{ "email": "mo@example.com" }
```

**202**, with no body, whatever happened.

That is the whole design of this endpoint. An address with no account, an
address that asked a moment ago and is rate limited, and a real address that
has just been mailed are **indistinguishable** from outside. A 404 for an
unknown address would turn this into a way to test which addresses are
registered, and unlike signup, which has to say when an address is taken to be
usable at all, nothing here needs to leak that.

Three consequences worth stating, because each one looks like a bug until you
know why:

- **The UI must not say "we sent you an email".** It says *if* that address has
  an account, a link is on its way. Copy that is confident about sending is
  copy that confirms the address exists.
- **Mail failures are swallowed.** `PasswordResetService` catches them and
  logs, because a provider outage would otherwise make a registered address
  answer 500 while an unknown one still answers 202. The cost is real: somebody
  whose mail genuinely failed is told to check their inbox and nothing arrives.
  There is no way to tell them without telling an attacker the same thing.
- **The rate limit is silent.** One request per account per 60 seconds, read
  from `password_reset_token.requested_at`. The second request inside the
  window sends nothing and still answers 202.

The rate limit exists because this endpoint sends mail to an address the caller
chooses, which makes it a way to flood somebody else's inbox and burn the
sender's reputation. A timestamp the table already holds is the cheapest
correct answer; it needs no Redis and no token bucket.

The link points at `RESET_LINK_BASE` with the token in the query string. The
token is 256 bits of url-safe Base64, stored only as a SHA-256 hash, and good
for 30 minutes and one use.

**422** a missing or malformed address. Shape only: nothing here validates that
the address exists, because answering that is the thing this endpoint refuses
to do.

---

## 17. `POST /api/auth/reset-password`

Finishes it.

**Request**

```json
{
  "token": "mTtRbW0...",
  "password": "a new password"
}
```

No email field. The token names the account, and accepting an address here
would mean either ignoring it or trusting it, and trusting it would let anyone
holding any valid token reset anybody.

`password` is 8 to 72 characters, the same bounds as signup. The ceiling is
BCrypt's: it ignores everything past 72 bytes, so a longer password would have
a silent dead tail.

**204**, and it clears `sb_access` and `sb_refresh`.

Clearing them is not tidiness. A reset revokes every refresh token the account
has, including this browser's, so whatever the page is still holding is already
dead and leaving it in place only produces a confusing 401 on the next request.

Revoking every session is the point of that: a password change that leaves the
old sessions alive is not a password change, because whoever prompted the reset
is still signed in everywhere they already were.

The reset does **not** sign you in. You have just chosen a password and typing
it once more is a second of friction against the chance that the link was
opened by the wrong person on a shared machine.

**422** an invalid, expired or already-used token:

```json
{
  "type": "https://screenbook.dev/problems/invalid-reset-token",
  "title": "Invalid reset link",
  "status": 422,
  "detail": "That reset link is invalid or has expired. Request a new one.",
  "instance": "/api/auth/reset-password"
}
```

One problem type for all three cases, so a caller holding a dead link cannot
tell which, and does not need to: the answer is the same either way, ask for
another.

Single use is enforced in one statement rather than a read then a write:

```sql
UPDATE password_reset_token
   SET used_at = :now
 WHERE token_hash = :hash
   AND used_at    IS NULL
   AND expires_at > :now
RETURNING user_id
```

Two simultaneous presentations therefore produce one winner with no lock and no
version column: the second updates zero rows and is rejected. `RETURNING` is
what makes that possible; without it the check and the write would be separate
statements and the gap between them is exactly the race.

Unlike `refresh_token`, there is no reuse detection. Rotation has a legitimate
client that has moved on to a replacement, so a second presentation means theft
and is worth revoking every session over. A reset token has no such story: the
password is already changed, so a repeat is a double-clicked link or a dead
one, and revoking again buys nothing.

**422** also covers a password outside the length bounds.

---

## 18. `GET /api/me`

**200** the same body as signup. **401** with no valid session.

The profile comes from the database, not from the token. The access token
carries only `sub`, so a changed display name is correct immediately rather than
fifteen minutes later when the token expires. A token is a credential, not a
cache.

---

## 19. `GET /api/me/bookings`

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
   appears in section 19.
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
- **Email verification.** Nothing proves an address belongs to whoever typed
  it, which is why section 19 matches on `user_id` and `booking_by_email` goes
  unused. Completing a password reset does prove control of an address, but
  only for the accounts that reset, so a feature built on it would work for a
  minority of users and real verification would still be needed.
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
