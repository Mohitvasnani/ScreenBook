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

**The mark** is a cinema screen: a curved accent arc over a rounded cream body,
in the same two tokens as everything else. It ships as `src/app/icon.svg` and
`apple-icon.png`, as a horizontal lockup at `public/logo.svg` for the footer,
and as an Open Graph card at `src/app/opengraph-image.png`, which is the one
that matters most given where this project gets shared. The source files each
carried 7.7KB of C2PA provenance metadata and it is stripped, because an asset
on every page load should not be 94 percent signature.

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

### 1. `/` Find a cinema

**Role:** pick a building. The entry point. It stopped being a showtimes list
in wave 5, when one cinema's screenings became the only listing anyone can
read: there are over six thousand upcoming screenings across two countries.

**Shows:** a full-bleed hero with a globe on the right, running off the edge of
the screen, and a panel on the left carrying an eyebrow, the heading, a
two-country toggle, a search box and a row of glass controls. Under it, a list
that depends on what has been chosen. With nothing chosen it is the forty
cities, grouped by country and biggest first, each with its cinema count, and
no cinemas at all. Choosing a city, from a card, the search box or the globe,
lists that city's cinemas as links.

**The city chips are gone, and the search box replaced them.** Five shortcuts
plus "All cities" was a reasonable row at 130 cinemas. At 980 it stopped being
one: London alone has 127, so a shortcut to a city no longer leads to a list
anybody scrolls, and picking five cities out of forty claims a ranking the data
does not support. The search box does the same job without the claim, and it
works with no country chosen, which the chips never did.

**The opening list is cities, not cinemas.** Listing all 980 cinemas as cards on
arrival answered a question nobody had asked, and the first thing anyone saw was
whichever city sorted first. It is now the forty cities from the rollup, ordered
by cinema count with the count on the card, so the order says what it is rather
than ranking the cities. The cinemas appear once one is picked. A city is a
button rather than a link because choosing one changes this page instead of
leaving it.

**The globe is a MapLibre map, not an image.** Globe projection over MapTiler's
dataviz-dark, with every country that is not GB or IN washed out in the page
background and the wash fading as you zoom. One accent pin per city, labelled
in a nine-patch chip, handing over to individual cinemas at z8.

**Action:** choose a country and the camera frames that whole country. Choose a
city, from the search box or from the globe itself, and it frames that city's
cinemas. Click a cinema and it flies to its street and names it in a popup,
with a link onward to what is on there. "Back to the globe" clears everything,
which is the only honest back when the camera is driven from state.

**Search does two different things on purpose.** A city filters the page. A
cinema navigates to it. That contradicts the map, where clicking a dot opens a
popup rather than leaving the page, and the contradiction is the point: a dot
can be hit by accident, a name you typed and then pressed cannot.

**The radius.** An origin plus a distance of 5, 10 or 15km. The origin is
either the device's location or, once a city is chosen, that city, offered as
its own button rather than as a side effect of picking the city: one action,
one effect, so browsing a city never silently puts the page into radius mode.
It is also what makes the feature usable by somebody not standing in it, which
is most people looking at a portfolio project. Inside the radius the cinemas
carry a distance and a from-price; outside it they are dimmed rather than
removed, because a cinema you will not drive to is still worth knowing is
there. An empty radius names the nearest cinema outside it instead of showing
an empty circle. Choosing a different place retires the radius rather than
leaving a panel reporting somewhere you have left.

**The routes.** With an origin and a radius, three cinemas get a line from the
origin: nearest, cheapest and furthest, in three colours chosen to sit away from
the accent, which is what every other cinema on the map already is. A cinema
that wins two categories is one line, coloured by the first and named for both
in the legend. The figure sits on the line as a label of its own rather than as
text along the path, because MapLibre cuts a line at tile edges and repeats the
text on every piece. The legend is the key, because three coloured lines with
no key is a puzzle. Lines start straight, using the distance the API already
sent. One button, "Show road routes", swaps in real geometry and adds a time,
and it is the only thing on the page that waits on an outside service, so it is
pressed and never triggered by dragging the radius. If it fails the legend says
so in one line and the straight lines stay: the feature goes back to what it was
rather than breaking.

**The camera has one rule:** frame what was asked about, derived from the same
rows the list is built from. A country frames that country's cinemas, a city
frames that city's, a radius frames its own ring. Only the opening planet is a
fixed camera, because it is deliberately framing nothing.

**Pitch is a ramp, not a constant.** The planet and a country take none, a city
takes 35 degrees, a street takes 50. Pitch is for arrival: the planet is an
object you look at and a country is a map you read, and neither is improved by
perspective. `fitBounds` also solves for a trapezoid under pitch and does it
only approximately, which over a country the size of India was enough to push
it into the corner of the frame. A radius takes none for a third reason: the
circle is a measurement, and at 42 degrees a 5km one draws half again as wide
as it is tall.

**States:** nothing chosen (planet, both countries' cities listed), country, city,
cinema, radius. Plus loading, error with retry, an empty radius, road routes
loading and unavailable, and a declined geolocation permission,
which is a quiet line of text rather than an error because the list underneath
is a complete way to use the site.

**Accessibility:** a canvas cannot be read out, so the list below is the
equivalent rather than a fallback: cities first, then the chosen city's cinemas
as links, and the page says so. The map is not hidden
from assistive tech, because that would hide the tile credit with it.

Its sibling `/cinemas/[cinemaId]` is the showtimes listing for one building:
the film, screen, start time in that cinema's own timezone, runtime, from-price
and seats free, read in two requests so the cinema's zone arrives before any
time is printed.

### 2. `/showtimes/[id]` Seat selection

**Role:** the core screen. Choose a seat and claim it.

**Shows:** a header with the film, screen and time, then a seat map of 120 seats
in 10 rows (A to J) of 12. Every seat carries its tier and its own price.

**Action:** tap up to ten available seats (five as a guest) to select them, tap again to
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
| `SiteFooter` | three columns over a bottom bar: the lockup and strapline, project links, contact. ODbL and TMDB credits live in the bottom bar | one | built in wave 7. It was a single flex row, which put required licence text and navigation in the same undifferentiated sentence |

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
| `CinemaSearch` | combobox over cities and cinemas, client-side | idle, typing, open with results, no match, city chosen | built in wave 7. A real combobox: `role="combobox"` with `aria-activedescendant` over a listbox, arrow keys, Enter, Escape. Closes on an outside press rather than on blur, because blur fires before the click that caused it and the pick would never land |

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
- **One to five seats per hold as a guest, one to ten signed in, all or
  nothing.** The cap is enforced by the server, not just the UI. If any seat is lost between choosing and holding,
  nothing is held, and the 409 names which seats went and what state each is in
- **Seats can be anywhere in the house:** any row, any gap, any tier. There is
  no adjacency rule, because two people in a nearly full hall have to be able to
  take the seats that are left. The cap is the only rule on which seats may be
  held together. A hold across tiers is priced per tier, so the checkout shows
  one block per tier with its own subtotal
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
