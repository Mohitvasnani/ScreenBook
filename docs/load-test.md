# Load test, wave 9B

What was measured before building anything in 9B, and what the numbers decided.
The scripts are in `tools/load/`. Raw k6 output goes to `tools/load/results/`,
which is not committed.

**These are relative numbers.** k6, the app, Postgres (in Docker) and the OS all
shared one Windows laptop, so none of this is a production figure and none of it
says what Render and Neon would do. It is good for comparing one configuration
against another on the same machine, and for finding where things queue.

## Setup

- Spring Boot 4.1.1, Java 21, Hikari pool of 10 (`maximum-pool-size`), local
  Postgres 18 with PostGIS, the seeded local data.
- SQL logging off (`SPRING_JPA_SHOW_SQL=false`). With it on, console output is
  measured as latency.
- k6 2.4.0, run natively. A first run through Docker's network layer was about
  20% slower and had one unexplained dial timeout, so it was discarded.
- Actuator metrics exposed only locally, through an environment variable
  (`MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE`). Production stays on
  `health,info`, because the security config permits every other path.
- Every set below follows a discarded warm-up. The first runs on a fresh JVM
  were visibly slower and kept improving for a few runs.

## Scenario A: seat map reads

`tools/load/seatmap-read.js`. `GET /api/showtimes/{id}/seats` for one screening,
ramping 10 to 50 virtual users with no pause between requests. That is a
saturation test, not a model of people: every user is always mid-request, so
latency is roughly users divided by throughput.

Three warm runs:

| | median of three | range |
|---|---|---|
| throughput | 730 req/s | 722 to 749 |
| median latency | 30 ms | 29 to 30 |
| p95 latency | 62 ms | 61 to 63 |

Pool, from one sampled run: `hikaricp.connections.active` sat at 10 (the whole
pool) from the first sample and `pending` climbed to a peak of 38, then drained
to 0. With 50 users and 10 connections, nearly everyone not holding a connection
was waiting for one, so most of that latency is queueing.

**Pool of 20.** One run gave 756 req/s, about 4% above the warm pool-of-10 runs.
That is a single run and is not distinguishable from drift between runs, so the
pool stays at 10. More connections also cost Neon capacity on the free tier.

## Scenario B: holds on a contested seat

`tools/load/hold-under-read-load.js`. Twenty races per run. In each, 8 guests
send `POST /api/holds` for the same seat at the same moment. Every race must
produce exactly one 201 and seven 409s. Optionally, readers hit the seat map of
the same screening during the races.

**Correctness.** Across the 10 recorded runs (200 races), no race check failed.
This held with the pool saturated by 50 readers.

**Why contested holds cost what they cost.** `HoldService.claim` locks each seat
row with `SELECT ... FOR UPDATE`, in ascending id order. Losers hold a pooled
connection while they wait for the winner to commit, then wake one at a time,
see the seat is HELD and get a 409. The tail latency of a race is that
serialization. The winner cannot be starved by the losers, because it already
holds its own connection for the whole transaction and never needs a second one.

Hold latency, median of three runs after a hold-path warm-up:

| | holds alone | holds under 50 readers |
|---|---|---|
| median | 81 ms | 164 ms |
| p95 | 137 ms | 247 ms |
| fastest request | 11 ms | 74 ms |

Under 50 saturating readers (500 to 800 req/s on one screening, five times the
pool size) a hold takes about twice as long and stays under 300 ms. This is a
real effect: holds and reads share one pool. It carries a caveat. Across the
three loaded runs the readers' throughput fell from 604 to 510 to 489 req/s,
so the machine was slowing between runs and some of the 2x may be drift.

## Scenario C: holds under realistic read load, interleaved

Same script with 10 readers (the size of the pool, so reads queue for nothing),
alone and loaded runs alternated so drift hits both equally.

| run | holds median | holds p95 |
|---|---|---|
| alone, 1 | 108 ms | 216 ms |
| 10 readers, 1 | 74 ms | 132 ms |
| alone, 2 | 95 ms | 145 ms |
| 10 readers, 2 | 51 ms | 74 ms |

The 10 readers themselves did 765 and 796 req/s at p95 of 17 to 20 ms, which is
an absurd read rate for one screening and still left no measurable penalty on
holds. The loaded runs were the faster ones, which is the order effect of
warm-up and not a benefit of load. The finding is the absence of a cost.

## Scenario D: the route endpoint under abuse

`tools/load/route-abuse.js`. Twenty virtual users for 20 seconds, a random
origin on every request so the route cache never answers. That is the worst case
for the provider's quota: every request is a cache miss that would cost a
provider call.

**Before the limiter.** The API key was blank, so every request answered 503
before any provider call: 47,741 requests, 2,369 req/s, all 503, p95 13 ms.
Every origin was different, so with a real key each of those would have spent
quota. The free plan allows 2,000 a day, which at this rate is gone in under a
second.

**After.** A dummy key, and the provider base URL pointed at a closed local port,
so a provider call is refused at once and no real quota is spent. Redis in
Docker, bucket capacity 30, refill 0.02 a second.

| run | bucket at start | 503 (reached the provider call) | 429 | req/s | p95 |
|---|---|---|---|---|---|
| 1, cold JVM | full | 30 | 13,640 | 673 | 55 ms |
| 2, warm | full | 30 | 32,335 | 1,563 | 18 ms |
| 3, warm | left empty by run 2 | 0 | 33,754 | 1,685 | 16 ms |

- Both full-bucket runs let exactly 30 requests through, which is the capacity.
  Twenty seconds of refill adds about 0.4 of a token, so it does not show.
- Run 3 did not reset the bucket. That shows it lives in Redis across runs: a
  drained bucket stays drained, and nothing got through.
- **The throughput is not comparable to the baseline.** Run 1 is a cold JVM,
  which the setup notes already warn about. Warm, the endpoint does 1,563 to
  1,685 req/s against 2,369. The gap is work the baseline skipped. A refused
  request still reads the cinema row, because the unknown-cinema check runs
  before the limiter, and then makes one Redis round trip. The limiter protects
  the provider's quota, not Postgres.
- What changed is the number of provider calls: unbounded before, 30 in a burst
  and then one every 50 seconds now.

The `Retry-After` header is covered by `RoutingRateLimitTest` and was not read
off the running app in this run.

## What this decided

- **Seat availability is not cached.** Reads interfere with holds only once read
  concurrency exceeds the pool, and even at five times the pool a hold stays
  under 300 ms. A cache would buy nothing at realistic load and would add a
  staleness bug to the one screen where staleness is the failure. If it ever
  mattered, a separate small pool for reads (a bulkhead) would isolate the
  writes without caching availability. Not built.
- **Idempotency stays in Postgres.** It already was (`V9`). The claim has to be
  as durable as the booking it guards, and a cache cannot share a transaction
  with the booking.
- **The pool stays at 10.** Doubling it was not measurably better.
- **The route endpoint is rate limited with one global bucket.** The quota
  belongs to the API key, so the bucket does too. Under 20 users sending only
  cache misses, 30 requests reached the provider and the rest were refused.
- **The route cache could not be judged yet.** Caffeine statistics were off, so
  there was no hit ratio. `recordStats` is now on and `cache.gets` is available.

## Not measured

- A run against Render and Neon. Free-tier CPU and network would dominate it.
- Booking confirmation under load, only holds.
- The limiter against Upstash. Scenario D used a Redis container on the same
  machine. A hosted Redis adds a network round trip to every cache miss.
- More than one app instance.

## Reproduce

```powershell
# app terminal, from backend/
$env:MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE = "health,info,metrics"
$env:SPRING_JPA_SHOW_SQL = "false"
./gradlew bootRun

# from the repo root, after a warm-up run
k6 run tools/load/seatmap-read.js
$env:READERS = "0"; k6 run tools/load/hold-under-read-load.js     # holds alone
$env:READERS = "10"; k6 run tools/load/hold-under-read-load.js    # under 10 readers
Remove-Item Env:READERS                                           # default is 50
```

Pool pressure while a run is going:
`/actuator/metrics/hikaricp.connections.active` and `.pending`.

Route abuse (Scenario D), with Redis from `docker compose up -d redis`:

```powershell
# app terminal, from backend/. A dummy key, and a provider that refuses at once.
$env:ORS_API_KEY = "dummy"
$env:SCREENBOOK_ROUTING_BASE_URL = "http://localhost:9"
./gradlew bootRun

# from the repo root. Empty the bucket before every run, it persists in Redis.
docker exec screenbook-redis redis-cli DEL rl:routing:provider
k6 run tools/load/route-abuse.js
```
