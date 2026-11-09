# ScreenBook Design Brief

Handoff spec for the screens. Describes what each screen is for, what data it
shows, and every state it has to handle. The visual direction is now settled and
recorded below. The constraints at the bottom are not open.

## What this is

A cinema seat booking app. You pick a screening, pick a seat, the seat is held
for you for five minutes, then you confirm and get a booking reference.

The engineering point of the project is that two people must never book the same
seat. One screen exists purely to show that happening. Everything else is an
ordinary booking flow.

Mobile-first. Must work at 375px wide.

---

## Visual direction

Settled, and recorded here so the screens and this document cannot disagree. All
of it lives in `frontend/src/css/tokens.css`, so a palette or type change is a
one-file change.

**Dark, single theme.** `color-scheme: dark`, background `#0d0e12`, surface
`#16181e`, ink `#f2eee6`. No light mode: a booking screen is read in a dark
room, and a second theme doubles every state there is to check.

**Accent** `#ff7a59`, for identity and focus rings. Deliberately separate from
the semantic state colours, so "the brand colour" and "this went wrong" can
never be the same decision.

**Semantic state.** `--color-positive`, `--color-warning`, `--color-critical`,
each with a tint for fills. Never used alone: every component that uses them
pairs the colour with a glyph or a word.

**Seat tiers** have three tints of their own, stepping up in lightness so they
survive greyscale. A tier tints a seat's outline and names itself in the
cross-aisle. It never carries state.

**Type.** Archivo for headings and the wordmark, loaded as a variable font so
its width axis is available. Inter for body. JetBrains Mono for anything read
character by character: booking references, HTTP codes, the countdown. Tabular
figures through a `.tnum` utility wherever numbers sit in a column.

---

## Pages (9)

### 1. `/` Showtimes

**Role:** pick a screening. The entry point.

**Shows:** a list of 5 showtimes. Each has film title, screen name, start time,
runtime, ticket price, and how many of 120 seats are free.

**Action:** tap a showtime, go to its seat map.

**States:** loading, loaded, empty (no screenings), error with retry.

### 2. `/showtimes/[id]` Seat selection

**Role:** the core screen. Choose a seat and claim it.

**Shows:** a header with the film, screen and time, then a seat map of 120 seats
in 10 rows (A to J) of 12. Every seat carries its tier and its own price.

**Action:** tap up to six available seats to select them, tap again to
deselect, then press Hold. That claims them for 300 seconds and moves to
checkout. The summary lists each chosen seat with its own price and a running
total, and each can be removed from the list as well as from the map.

**States:** loading skeleton, loaded, unknown showtime (404), load error.

**The conflict state matters most:** between the map rendering and the user
pressing Hold, someone else can take one of those seats. The request comes back
409 naming which seats went and what state each is in now, so they flip in place
and the rest stay selected. This is not an error page, it is a normal thing that
happens, and the copy has to say plainly that *nothing* was held, because
all-or-nothing is not what a user assumes.

### 3. `/hold/[holdId]` Checkout

**Role:** confirm the held seat before the hold lapses.

**Shows:** the held seats with their individual prices, the showtime, the
total, and a countdown to expiry.

**Action:** confirm, which converts the hold into a booking.

**States:** loading, active (timer running), urgent (under 60 seconds left),
expired, already confirmed, submitting, error.

The countdown is the centre of this screen. It should create mild urgency
without feeling like a scam countdown.

### 4. `/bookings/[ref]` Confirmation

**Role:** proof of booking. Reloadable and shareable by URL.

**Shows:** booking reference (`SB-7F3K2Q`), film, screen, start time, one line
per seat with what that seat cost, the total paid, time booked.

**Action:** none, terminal screen. Copying the reference is useful.

**States:** loading, loaded, unknown reference (404).

The reference is the hero. It is designed to be read aloud, so it needs to be
large and unambiguous.

### 5. `/race` Concurrency demo

**Role:** make the invisible engineering visible. Fires two hold requests at the
same seat at the same instant and shows that exactly one wins.

**Shows:** a showtime and seat picker, a Fire button, then two request lanes side
by side, each with its method and path, status code, response time, and outcome.
One reads 201 WON, the other 409 LOST.

**States:** idle, in flight, resolved, plus a permanent note saying whether this
is running against the mock or the real API.

This page is the reason the project exists. It is the one screen a technical
viewer will remember.

---

### 6. `/login` and `/signup` Accounts

**Role:** optional. Everything books without them.

**Shows:** one field set. Login takes email and password; signup adds a name.

**Action:** on success the session arrives as httpOnly cookies and the user is
returned to wherever they were, via a `?next=` path that is validated rather
than trusted.

**States:** idle, submitting, field error (signup only), form error, already
signed in (redirects away), demo mode (explains that accounts need the live
API, because a service worker cannot reproduce httpOnly cookies, CSRF and
refresh rotation).

Login is deliberately vague about which half was wrong, because the API answers
"no such account" and "wrong password" identically and splitting them here
would hand anyone a way to test which addresses are registered. Signup cannot
be vague, since it has to say an address is taken or you can never finish. That
asymmetry is accepted, not overlooked.

### 7. `/account` and `/account/bookings` The account

**Role:** see who you are, and every booking belonging to you.

**Shows:** name, email, join date, seat allowance. The list shows each booking
newest first, linking to the public confirmation page rather than duplicating
it.

**States:** waiting for the session check, guest (redirects to login with a
`?next=` back), loaded, empty, load error, demo mode.

**The state worth designing:** empty. Someone who booked as a guest and then
signed up with the same address finds nothing here and concludes their booking
is gone. It is not, and the screen says why: an unverified address is not proof
of ownership, so the list matches on the account only, and guest bookings stay
reachable by their reference.

---

## Components

### Shell
| Component | Role | States | Status |
|---|---|---|---|
| `SiteHeader` | wordmark, mode badge, race demo link, account state | default, scrolled, guest, signed in, session unknown | built. The third auth state is the point: before the startup check lands, "no user" and "not yet known" are the same value, so the slot renders nothing rather than showing Sign in to someone already signed in |
| `ModeBanner` | one line: mock data or live API | demo, live | built: sits above the sticky header, so it scrolls away |
| `SiteFooter` | links to the repo and the API docs | one | built |

### Showtimes
| Component | Role | States |
|---|---|---|
| `ShowtimeCard` | one screening | default, hover, focus, nearly full, sold out (not tappable) |
| `ShowtimeList` | the list | loading, loaded, empty, error |

### Seat selection
| Component | Role | States |
|---|---|---|
| `ScreenCurve` | the "SCREEN" marker above the grid | one |
| `SeatMap` | the grid, including aisles | loading, loaded, fit, zoom |
| `Minimap` | the whole house at a glance with the visible part boxed | only in zoom, and only when the stage overflows |
| `SeatRow` | row letter at both ends plus 12 seats | one |
| `Seat` | **the hard one.** One seat | available, selected, held by someone else, booked, hover, keyboard focus, pending |
| `SeatLegend` | key to the four seat states | one |
| `SelectionSummary` | chosen seats with prices, removal, running total, Hold button | nothing selected, within cap, cap reached, submitting |
| `ConflictNotice` | which seats went and what state each is in now | one lost, several lost |

### Checkout
| Component | Role | States |
|---|---|---|
| `HoldCountdown` | time left on the hold | comfortable, urgent, expired |
| `HeldSeatSummary` | film, screen, time, seat, price | one |
| `ConfirmButton` | convert hold to booking | idle, submitting, disabled |
| `ExpiredNotice` | hold lapsed, with a route back | one |
| `GuestEmail` | optional address for the confirmation | guest (input), signed in (replaced by a line naming the account) |

### Confirmation
| Component | Role | States |
|---|---|---|
| `TicketStub` | the booking itself | one |
| `CopyReference` | copy the code | idle, copied |

### Race demo
| Component | Role | States |
|---|---|---|
| `RaceSetup` | showtime and seat pickers, Fire button | idle, firing |
| `RequestLane` | one of the two requests | waiting, in flight, won, lost |
| `OutcomeBadge` | 201 WON / 409 LOST | won, lost |
| `ModeCaveat` | what this proves in each mode | demo, live |

### Shared
| Component | Role | States | Status |
|---|---|---|---|
| `Button` | one button style for the whole site | idle, hover, focus, loading, disabled | not built: each screen still styles its own |
| `StatusPill` | short label for a state or an HTTP code | neutral, positive, warning, critical | built |
| `Skeleton` | loading placeholder shaped like the content it replaces | block, text | built |
| `ErrorState` | load failed, shows the problem detail | with retry, without | built |
| `EmptyState` | nothing to show, and that is the right answer | with action, without | built |

Two rules these encode. Every `StatusPill` tone carries a glyph as well as a
colour, so won and lost stay distinguishable in greyscale, to a colour-blind
reader, and in a screenshot. And `Skeleton` is `aria-hidden`: the container
announces the load once, rather than a dozen grey rectangles announcing
themselves.

`ErrorState` prefers the API's own `detail` over a generic line, because every
failure arrives as RFC 9457 problem details and the server usually has a better
sentence than "something went wrong".

---

## The five real design problems

Worth spending the effort here. Everything else is straightforward.

1. **120 seats at 375px wide.** Twelve seats across leaves roughly 26px each
   including gutters. Decide: scale down, scroll horizontally, or zoom.
   **Settled, then revisited:** one scaled view was the first answer, on the
   grounds that a zoom step adds a navigation mode to the one screen that
   matters. Multi-seat selection changed the facts: picking one seat at 26px is
   fine, picking six in a row is fiddly. There are now two views, Fit and Zoom,
   with Fit the default because "where is there space" is the first question
   and Zoom answering "can I hit the one I want".
2. **Four seat states without relying on colour.** Red and green is the obvious
   choice and the worst one. Shape, fill pattern, border weight, opacity or a
   glyph has to carry the meaning too.
   **Settled:** two independent axes. State sets fill, border style and glyph;
   tier sets outline tint and silhouette, a headrest bar for Premium and
   armrests for Executive. Colour is never the only signal on either axis, and
   keeping them separate means a tier can be added without touching a state
   rule.
3. **Aisles.** What separates a cinema from a grid of squares is the gaps. Decide
   where they fall and how rows are labelled.
   **Settled:** 3 | 6 | 3 with two walking aisles, so the best seats sit in a
   six-wide centre block. Cross-aisles fall wherever the tier changes rather
   than at hardcoded rows, and each carries the tier name, so the gap doubles as
   the section label and there is no legend row to keep in sync. Row letters at
   both ends.
4. **The countdown.** Urgency without anxiety, and not through colour alone.
5. **The race page.** A developer-facing screen inside a consumer-facing app.
   Decide whether it matches the rest of the site or deliberately looks like a
   debugging tool.

---

## Fixed. Not open to design

These come from the API and changing them means changing the backend.

- 10 rows labelled A to J, 12 seats per row, 120 seats total, one screen layout
- A hold lasts 300 seconds
- **One to three seats per hold as a guest, one to six signed in, all or
  nothing.** The cap is enforced by the server, not just the UI. If any seat is lost between choosing and holding,
  nothing is held, and the 409 names which seats went and what state each is in
- **Seats must be adjacent:** one row, no gaps. Aisles do not break a run, so
  seats 3 and 4 count as neighbours. Enforced by the server as well as the UI.
  The consequence is accepted rather than overlooked: on a busy screening a
  group may find no row with enough seats together and be unable to book
- Prices in GBP
- Three seat tiers: rows A to E Normal, F to H Premium, I to J Executive. The
  tier arrives on every seat from the API, so the client never derives it from
  the row letter
- Per-seat pricing. The showtime carries a base price and the tier an uplift, in
  pence: Normal 0, Premium +250, Executive +500. The API resolves the sum and
  sends `priceMinor` on each seat; the client never does that arithmetic. The
  price on the showtimes list is the lowest tier, so it reads as "from"
- No payment step. Nothing is charged
- **Accounts are optional and change exactly three things:** the seat cap, 3
  as a guest and 6 signed in; attribution, so a booking confirmed while
  signed in appears in my-bookings; and nothing else. Every booking endpoint
  behaves identically with or without a session. Guest checkout is the
  default path, not a fallback
- **Booking ownership is decided at confirm, not at hold.** A guest can pick
  seats, sign in, and the booking is theirs
- **My-bookings matches on the account, never on the email.** The address on
  a booking is unverified, so matching on it would hand a stranger's history
  to anyone who signs up with their address
- Booking references are `SB-` followed by 6 characters
- Seat states are exactly four: AVAILABLE, HELD, BOOKED, and the user's own
  SELECTED before they press Hold
- Must work at 375px
