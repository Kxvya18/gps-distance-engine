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
| Trips and pings | 10,000 trips, 15.05M pings |
| Run time with cache | 16.3 s, including generating the trips (615 trips/s) |
| Engine time per trip | p50 1.90 ms, p99 53.88 ms |
| Peak heap seen | 233 MB |
| Audit failures | 0 |

**Provider calls**

| Approach | Calls |
|---|---|
| One call per gap | 20,594 |
| Batched per trip | 10,000 |
| Batched and cached | 2,098 (90% fewer) |

The cache result depends on the simulator repeating 300 fixed corridors, as shift routes do. A real hit rate must be measured on real traffic.

**Accuracy against the simulator's true distance**

| Method | Mean absolute error | Mean signed error |
|---|---|---|
| Naive sum of pings | 7.56% | +5.95% |
| This engine | 1.26% (worst trip 2.90%) | -1.26% |

The naive sum over-counts here because false fixes add more than the tunnels lose. The problem statement describes under-payment, and the real direction depends on the data. These figures apply to simulated trips only, on a street grid where road distance is known exactly, and real GPS may behave differently. The fake provider's 20 ms latency is an assumption.

**Failure run:** the provider died halfway through 10,000 trips. All trips finished, 1,250 were marked `DEGRADED`, and every one was recomputed after recovery to exactly the same metres as the healthy run.

## API

| Method and path | Roles | Purpose |
|---|---|---|
| `POST /api/auth/login` | Anyone | Get a token |
| `POST /api/trips` | Admin, operator | Submit a trip |
| `GET /api/trips/{id}` | All, employees for their own trips only | Result with every segment |
| `POST /api/trips/{id}/late-pings` | Admin, operator | Add late pings |
| `POST /api/trips/{id}/close` | Admin, operator | Close the invoice |
| `GET /api/trips/{id}/invoice` | Admin, operator | Invoice and corrections |
| `POST /api/admin/provider?down=true` | Admin | Switch the fake provider off for demos |
| `GET /actuator/prometheus` | Admin, operator | Metrics |
| `GET /actuator/health` | Anyone | Health |

Demo users are `admin-a`, `operator-a`, `emp-1` and `admin-b`, with password `demo-password`. The default secrets in `docker-compose.yml` are for local use only.

## How it works

1. **Clean:** remove duplicates, sort, drop weak fixes and teleports.
2. **Anchor:** add pickup, drop, no-show and office points.
3. **Measure:** apply the ordered rules to each pair of points. See [docs/rules.md](docs/rules.md).
4. **Audit:** a separate class that shares no engine code rechecks every segment before billing.
5. **Store:** results and corrections commit together. A closed invoice never changes.

The architecture, sequence diagram and failure-mode table are in [docs/design-note.md](docs/design-note.md).

## Guarantees and their tests

| Guarantee | Test |
|---|---|
| Same input gives exactly the same result, in any order | `DistanceCalculatorTest` |
| Every billing rule, including boundaries | `SegmentRuleEngineTest` |
| A bad burst leaves the distance unchanged | `DistanceCalculatorTest`, `PingCleanerTest` |
| Provider failure, then exact recovery | `ProviderFailureTest` |
| Late pings make a signed correction and a replay changes nothing | `RecomputeServiceTest` |
| Concurrent late pings lose nothing | `RecomputeServiceTest`, `ConcurrencyTest` |
| Tenant isolation and roles | `TenantIsolationTest` |
| The auditor catches tampered results | `TripAuditorTest` |

There are 75 tests. Separately, the running stack was tested with `kill -9` while a trip was degraded, and the trip recovered after restart. That check was manual.

## Assumptions

- Fixes worse than 50 m are low accuracy.
- Logout trips anchor the office at the start.
- The other spec gaps are listed in [docs/rules.md](docs/rules.md).

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

Prometheus metrics include `trips_processed_total`, `trips_degraded_waiting`, `trips_failed_audit_total`, `invoice_corrections_total`, `provider_failures`, `provider_retries`, `provider_breaker_open`, `route_cache_hits`, `route_cache_misses` and `trip_update_retries_total`. Logs are structured JSON and carry trip ids, never locations or contact details.

## Trade-offs

A trip is always answered, using an estimate if needed, but an estimate can never be billed. Availability applies to reads and consistency applies to money. The full list, and what was left out, is in the [design note](docs/design-note.md).
