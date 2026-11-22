# Observability

Micrometer metrics through Actuator, scraped by Prometheus and drawn by Grafana.
It runs locally behind a compose profile. The deployed service does not expose
it, on purpose.

## Running it

```bash
docker compose --profile observability up -d
```

Then start the backend with the scrape endpoint switched on for that run:

```powershell
$env:MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE = "health,info,metrics,prometheus"
./gradlew bootRun
```

| | |
|---|---|
| Prometheus targets | http://localhost:9090/targets, the `screenbook` target should say UP |
| Grafana | http://localhost:3001, the ScreenBook dashboard is the home page |
| Clean view for a screenshot | http://localhost:3001/d/screenbook-overview?kiosk&from=now-5m&to=now&refresh=5s |

Everything is provisioned from files in `observability/`: the Prometheus scrape
config, the Grafana datasource and the dashboard JSON. Nothing is built by
clicking, so the dashboard is the same on any machine.

## What each panel answers

| Panel | Question | Query, in short | Decision behind it |
|---|---|---|---|
| Request rate by endpoint | What is being hit | `rate(http_server_requests_seconds_count)` by `uri` | |
| p95 latency by endpoint | Where is it slow | `histogram_quantile(0.95, ...)` over the `_bucket` series | Why seat availability is not cached |
| Connection pool | Is the pool saturating | `hikaricp_connections_active`, `_pending`, `_max` | Why the pool stays at 10 |
| Route cache hit ratio | Is the route cache earning its keep | `cache_gets_total` hit over all | Whether the cache should move into Redis |
| Rate limiter decisions | Is the limiter refusing, and is it still working | `screenbook_ratelimit_decisions_total` by `outcome` | Fail open, and the single global bucket |
| Route endpoint responses by status | What does the caller see | status split on the route endpoint | 429 is the limiter, 503 is a provider failure |

The cache panel reads near zero in a route-abuse run. A route only enters the
cache after a successful provider call, and the abuse setup uses a provider that
always refuses. It becomes useful with a real key and repeated origins.

## Decisions

- **Production exposes `health` and `info` only.** `SecurityConfig` ends in
  `permitAll`, so an exposed `/actuator/prometheus` would be public. Securing it
  needs a credential, which is more than a free-tier demo is worth.
  `ActuatorExposureTest` reads the real property file and asserts the endpoints
  are a 404, so a regression fails the build and a shell variable set for local
  metrics cannot hide one.
- **Percentile histograms, not client-side percentiles.**
  `management.metrics.distribution.percentiles-histogram.http.server.requests`
  publishes buckets and Prometheus works out the quantile. A p95 computed inside
  the app cannot be combined across instances. The cost is a few dozen bucket
  series per endpoint.
- **The limiter counter is tagged by outcome only** (`allowed`, `denied`,
  `unavailable`). A tag for the bucket key would grow with every caller.
  `unavailable` is the fail-open count: a request let through because Redis could
  not be reached, which means the protection has stopped.
- **The `uri` tag is the route template**, such as `/api/cinemas/{cinemaId}/route`,
  so the number of series is bounded by the number of endpoints and not by the
  number of cinemas.

## What it does not do

No alerting, no tracing, no log aggregation, and nothing in production. A hosted
Grafana with a secured scrape path would be the next step if the deployed
service ever needed watching.

## One gotcha

A bind mount whose source file does not exist is created by Docker as an empty
**directory**, and the container then fails with "not a directory". If a
compose service refuses to start with that message, check that the mounted file
exists at exactly that path before anything else.
