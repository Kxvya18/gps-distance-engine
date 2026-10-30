# GPS Distance Engine

MoveInSync SDE case study 1: GPS gap backfill and billable distance reconstruction.

Cab vendors are paid per km, and GPS loses data in tunnels and basements. This service rebuilds each trip's distance from pings and known stops, calls a routing provider only for real gaps, and labels every segment with the rule that produced it.

Stack: Java 17, Spring Boot 4, Postgres, Maven.

## Run it

```bash
docker compose up --build
```

The API is on port 8080. If that port is taken, run `APP_PORT=8090 docker compose up --build`. Then run the demo, which needs `curl` and `jq`:

```bash
./scripts/demo.sh
```

Run the tests, which use an in-memory database and need no Docker:

```bash
./mvnw test
```

Run the benchmark:

```bash
./mvnw -q -DskipTests compile
java -Xmx2g -cp target/classes com.moveinsync.distance.benchmark.BenchmarkRunner 10000 20
```

## Results

Measured on an Apple M2 with 8 cores and 16 GB RAM, with other Docker workloads running. Full output is in [docs/benchmark-results.txt](docs/benchmark-results.txt).

| Measure | Result |
|---|---|
| Trips and pings | 10,000 trips, 15.05M pings (about 1,505 per trip) |
| Run time with cache | 8.3 s, including generating the trips (1,202 trips/s) |
| Engine time per trip | p50 0.57 ms, p99 30.13 ms |
| Peak heap seen | 154 MB |
| Trips where two runs disagree on the metres | 0 |
| Audit failures | 0 |

**Provider calls**

| Approach | Calls |
|---|---|
| One call per gap | 20,594 |
| Batched per trip, before caching | 10,000 (1.00 per trip) |
| Batched and cached | 2,104 (0.21 per trip, 90% fewer) |

Each trip's result also reports its own `providerCalls`.

The cache result depends on the simulator repeating 300 fixed corridors, as shift routes do. A real hit rate must be measured on real traffic.

**Accuracy against the simulator's true distance**

| Method | Mean absolute error | Mean signed error |
|---|---|---|
| Naive sum of pings | 7.57% | +5.98% |
| This engine | 1.26% (worst trip 2.73%) | -1.26% |

The naive sum over-counts here because false fixes add more than the tunnels lose. The problem statement describes under-payment, and the real direction depends on the data. These figures apply to simulated trips only, on a street grid where road distance is known exactly, and real GPS may behave differently. The fake provider's 20 ms latency is an assumption.

**Failure run:** the provider died halfway through 10,000 trips. All trips finished, 1,266 were marked `DEGRADED`, and every one was recomputed after recovery to exactly the same metres as the healthy run.

## API

| Method and path | Roles | Purpose |
|---|---|---|
| `POST /api/auth/login` | Anyone | Get a token |
| `POST /api/trips` | Admin, operator | Submit a trip |
| `GET /api/trips/{id}` | All, employees for their own trips only | Result with every segment |
| `POST /api/trips/{id}/late-pings` | Admin, operator | Add late pings |
| `POST /api/trips/{id}/close` | Admin, operator | Close the invoice. Add `?reviewed=true` for a trip flagged `NEEDS_REVIEW` |
| `GET /api/trips/{id}/invoice` | Admin, operator | Invoice and corrections |
| `POST /api/admin/provider?down=true` | Admin | Switch the fake provider off for demos |
| `GET /actuator/prometheus` | Admin, operator | Metrics |
| `GET /actuator/health` | Anyone | Health |

Demo users are `admin-a`, `operator-a`, `emp-1` and `admin-b`, with password `demo-password`. The default secrets in `docker-compose.yml` are for local use only.

## How it works

The request carries raw pings, trip milestones, planned stops, ad-hoc riders and the office location. See `scripts/sample-trip.json`.

1. **Clean:** remove duplicates, sort, drop weak fixes and teleports.
2. **Anchor:** add pickup, drop, no-show and office points.
3. **Measure:** apply the ordered rules to each pair of points. See [docs/rules.md](docs/rules.md).
4. **Audit:** a separate class that shares no engine code rechecks every segment before billing.
5. **Store:** results and corrections commit together. A closed invoice never changes.

**Rounding:** distance is held in whole metres as a `long`. Each segment is rounded once to the metre, the total is their exact sum, and the billable figure is rounded once, half up, to 0.1 km. Haversine uses `StrictMath`, so results match on every machine.

**Rule version:** the rule order is fixed in code. Every limit and the no-show flag are set under `app.rules` in `application.yml`, and every result stores its rule version. Any change to a limit must raise `app.rules.version`.

The architecture, sequence diagram and failure-mode table are in [docs/design-note.md](docs/design-note.md).

## Guarantees and their tests

| Guarantee | Test |
|---|---|
| The two worked examples, with their exact numbers | `PdfExamplesTest` |
| Same input gives exactly the same result, in any order, with or without the cache | `DistanceCalculatorTest` |
| Fallback order for riders, no-shows and the office | `AnchorBuilderTest` |
| Limits and the no-show flag are configurable | `RuleConfigurationTest` |
| Rounding | `TripResultTest` |
| Every billing rule, including boundaries | `SegmentRuleEngineTest` |
| A bad burst leaves the distance unchanged | `DistanceCalculatorTest`, `PingCleanerTest` |
| Provider failure, then exact recovery | `ProviderFailureTest` |
| Automatic recompute with the real scheduler | `AutomaticRecoveryTest` |
| Provider timeouts and partial batch failure | `ResilientRouterTest`, `DistanceCalculatorTest` |
| Late pings make a signed correction and a replay changes nothing | `RecomputeServiceTest` |
| A flagged trip's correction waits for review | `RecomputeServiceTest` |
| Closing and late pings at the same moment stay correct | `RecomputeServiceTest` |
| Database outage gives a safe 503 | `DatabaseDownTest` |
| Concurrent late pings lose nothing | `RecomputeServiceTest`, `ConcurrencyTest` |
| Tenant isolation and roles | `TenantIsolationTest` |
| No personal data in logs or errors | `LogPrivacyTest`, `TenantIsolationTest` |
| The auditor catches tampered results | `TripAuditorTest` |

There are 109 tests. Separately, two manual checks ran on the full Docker stack: `kill -9` while a trip was degraded, which recovered after restart, and stopping Postgres, which gave a 503 and recovered when it returned.

## Assumptions

- Fixes worse than 50 m are low accuracy.
- A milestone with coordinates but no time counts as invalid and uses its fallback.
- Logout trips anchor the office at the start. The spec's end-of-trip wording would add a return leg of about 30 km to its own 31 km logout example, so the rule is applied at the office end of the trip.
- The other spec gaps are listed in [docs/rules.md](docs/rules.md).

## Object-oriented design

- **Layers:** `TripController`, then `TripService`, then `TripStore` and the repositories. The core algorithm is in plain classes with no Spring.
- **Encapsulation:** records are immutable, and each class keeps its state private, such as `CircuitBreaker`'s state.
- **Inheritance:** `FakeRoutingProvider` extends `BaseRoutingProvider`, which holds what all providers share. Errors extend `ApiException`.
- **Polymorphism:** the engine depends on the `Router` and `RoutingProvider` interfaces, and each `ApiException` subclass supplies its own HTTP status.

## Cost and scaling

| Step | Time | Space |
|---|---|---|
| Sort and deduplicate | O(n log n) | O(n) |
| Teleport filter | O(n × c), where c is the number of chains. Usually small, O(n²) on pure noise | O(n) |
| Rules and totals | O(n) | O(n) |
| Route cache lookup | O(1) | O(entries), capped at 200,000 |

Here n is pings per trip. Trips are independent, so a thread pool scales them across cores.

**Where it stops scaling**

- **Provider limits** are the first real limit, since calls wait on the network.
- **One JVM:** the cache and breaker live in one process. Several instances would need shared state, for example Redis.
- **Storage:** evidence takes about 160 KB per trip, so 10,000 trips a day would add about 1.6 GB a day. That is an estimate from the sample trip.

## Caching

Route answers are cached by snapped start and end cell, with LRU eviction at 200,000 entries and a 24-hour TTL. A cached route can be up to 24 hours stale. The cache is lost on restart, which costs only extra provider calls. Finished results are not cached in memory. Replays are answered from Postgres by evidence hash.

## Monitoring

| On-call question | Prometheus metric |
|---|---|
| Lag | `trips_degraded_oldest_age_seconds` |
| Error rate | `http_server_requests_seconds_count`, `provider_failures`, `provider_timeouts` |
| Rejections | `segments_rejected_total`, `trips_failed_audit_total` |
| Retries | `provider_retries`, `trip_update_retries_total` |
| Queue depth | `trips_degraded_waiting` |
| Provider health | `provider_breaker_open`, `route_cache_hits`, `route_cache_misses` |

Logs are structured JSON and carry trip ids, never locations or contact details.

## Trade-offs

A trip is always answered, using an estimate if needed, but an estimate can never be billed. Availability applies to reads and consistency applies to money. Latency is bounded by a 2-second provider timeout, and accuracy is traded for speed by the fallback estimate. The full list, and what was left out, is in the [design note](docs/design-note.md).
