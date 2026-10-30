# Risk register

Everything this project does not do, might get wrong, or has not proved. Each entry says how much it matters, who it affects, and what would fix it.

**How to read severity:**

| Severity | Meaning |
|---|---|
| High | Must be solved before real production use |
| Medium | Should be solved, with a working mitigation today |
| Low | Worth knowing, small effect |

## Summary

| ID | Risk | Severity |
|---|---|---|
| D1 | Accuracy measured on simulated trips only | High |
| D2 | Fake routing provider | High |
| D3 | Cache savings depend on repeating routes | Medium |
| D4 | Engine reads slightly low | Low |
| D5 | Stationary GPS jitter adds distance | Low |
| T1 | Automated tests use H2, not Postgres | Medium |
| T2 | Crash and database checks on Docker are manual | Low |
| T3 | The close race was never reproduced | Low |
| T4 | Crash mid-save not fault-injected | Low |
| T5 | Measured on one laptop | Low |
| T6 | Mutation testing covers the engine only | Low |
| B1 | Road answers can change over time | Medium |
| B2 | Old rule versions can't be re-run | Medium |
| B3 | The rule version is raised by hand | Medium |
| B4 | Who reviewed a trip isn't recorded | Medium |
| B5 | A trip end with no time adds no office anchor | Low |
| B6 | The logout rule is an interpretation | Medium |
| S1 | One app instance only | Medium |
| S2 | Evidence storage grows quickly | Medium |
| S3 | Concurrent cache misses pay twice | Low |
| S4 | Teleport filter is O(n²) on pure noise | Low |
| S5 | Recompute stops at the first failing trip | Low |
| X1 | Hard-coded demo users | High |
| X2 | No login rate limit or token revocation | High |
| X3 | Stored evidence isn't encrypted | High |
| O1 | No schema migrations | High |
| O2 | Retry pause has no jitter | Low |
| O3 | Default secrets in `docker-compose.yml` | High |

## Data and accuracy

### D1. Accuracy measured on simulated trips only

- **What:** every accuracy figure comes from `TripSimulator`, on a street grid with a known true distance.
- **Effect:** real GPS and real roads may behave differently, so the figures aren't a claim about real trips.
- **Fix:** compare against real trips with odometer readings.

### D2. Fake routing provider

- **What:** `FakeRoutingProvider` adds north-south and east-west distance. Its delay and rate limit are assumptions.
- **Effect:** real latency, batch limits and failure patterns are unknown.
- **Fix:** add a real provider by extending `BaseRoutingProvider`, then tune the timeout and batch size.

### D3. Cache savings depend on repeating routes

- **What:** the simulator repeats 300 fixed corridors.
- **Effect:** the cached call count is only as real as that pattern.
- **Fix:** measure the hit rate on real traffic with `route_cache_hits`.

### D4. Engine reads slightly low

- **What:** the average signed error is about -1.3%, on every simulated trip type.
- **Effect:** a small, consistent under-count.
- **Likely cause:** straight lines cutting street corners. This wasn't measured separately.
- **Fix:** check against real trips before adjusting anything, because the rules come from the case study.

### D5. Stationary GPS jitter adds distance

- **What:** the short-gap rule adds up tiny movements while a cab waits.
- **Effect:** a small over-count. The rule order is fixed by the case study, so it was left as is.
- **Fix:** add a stationary filter as a new rule version, if MoveInSync agrees.

## Testing

### T1. Automated tests use H2, not Postgres

- **What:** `./mvnw test` uses an in-memory database in PostgreSQL mode.
- **Effect:** row locking and SQL details can differ. Postgres was exercised by the Docker demo, the load test and manual checks.
- **Fix:** run the same tests on a real Postgres with Testcontainers.

### T2. Crash and database checks on Docker are manual

- **What:** `kill -9` and stopping Postgres were run by hand. `DatabaseDownTest` simulates the outage with a mock.
- **Fix:** script them as a Docker-based failure suite.

### T3. The close race was never reproduced

- **What:** the lost-correction race didn't appear in 40 rounds before the fix. The double-close bug did reproduce.
- **Effect:** the test guards the behaviour, and the row lock prevents the race by design. Nothing proves the race would ever have happened.
- **Fix:** force the ordering with test hooks.

### T4. Crash mid-save not fault-injected

- **What:** a crash inside a save relies on the database transaction, and was not tested directly.
- **Fix:** kill the app during a save in a failure suite.

### T5. Measured on one laptop

- **What:** all timings come from one Apple M2 laptop, with other containers stopped.
- **Effect:** a server would give different numbers, and laptop timings can vary between runs.
- **Fix:** run on the target hardware.

### T6. Mutation testing covers the engine only

- **What:** PIT ran on `core`, `audit`, `routing` and `model`, using their own tests. 43 of 321 planted bugs were missed.
- **Effect:** the score says nothing about the Spring layers.
- **Fix:** extend it to the service layer, which is slower to run.

## Billing logic

### B1. Road answers can change over time

- **What:** a real provider can return different distances as maps change.
- **Effect:** a recompute after late pings could also shift by that drift, which breaks "same km" for that part of the trip.
- **Fix:** store each trip's route answers and reuse them on recompute.

### B2. Old rule versions can't be re-run

- **What:** stored results keep their version, but only the current rules exist in code.
- **Effect:** a past bill can be read, not re-derived, after a rule change.
- **Fix:** keep every rule version runnable.

### B3. The rule version is raised by hand

- **What:** changing a limit without raising `app.rules.version` would make the same version give different km.
- **Fix:** derive the version from a hash of the limits.

### B4. Who reviewed a trip isn't recorded

- **What:** any admin or operator can send `reviewed=true`, and no name is stored.
- **Effect:** there's no audit trail for that decision.
- **Fix:** store the reviewer and time.

### B5. A trip end with no time adds no office anchor

- **What:** the rule needs the trip-end time, and without one the office point is skipped.
- **Effect:** the last stretch of a LOGIN trip could be missed.
- **Fix:** fall back to the last point's time, once the rule is agreed.

### B6. The logout rule is an interpretation

- **What:** the case study's office rule contradicts its own logout example. The code anchors the office at the start of LOGOUT trips.
- **Effect:** if MoveInSync meant something else, logout totals change.
- **Fix:** ask them. It's one place in `AnchorBuilder`.

## Scale

### S1. One app instance only

- **What:** the route cache and circuit breaker live in one JVM.
- **Effect:** a second instance would have its own cache and its own view of provider health.
- **Fix:** Redis for both.

### S2. Evidence storage grows quickly

- **What:** evidence takes about 105 KB per trip in Postgres, measured in [E13](RESULTS.md#e13-how-does-the-real-api-behave).
- **Effect:** 10,000 trips a day is about 1 GB a day.
- **Fix:** move evidence to object storage.

### S3. Concurrent cache misses pay twice

- **What:** two threads missing the same route at once both call the provider.
- **Effect:** a little extra cost, with no effect on the km.
- **Fix:** share one in-flight call per key.

### S4. Teleport filter is O(n²) on pure noise

- **What:** each ping checks every open chain, and pure noise opens one chain per ping.
- **Effect:** slow only on data that is entirely false. [RESULTS.md](RESULTS.md) shows the numbers.
- **Fix:** cap the number of chains.

### S5. Recompute stops at the first failing trip

- **What:** a pass ends as soon as one trip stays degraded, on the assumption the provider is still down.
- **Effect:** a trip that keeps failing for another reason could hold back those after it.
- **Fix:** skip it and carry on, with a retry limit.

## Security

### X1. Hard-coded demo users

- **What:** four users in `UserDirectory`, sharing one password.
- **Fix:** connect a real identity provider.

### X2. No login rate limit or token revocation

- **What:** passwords can be tried repeatedly, and a token stays valid for up to one hour.
- **Fix:** rate limiting, and short tokens with refresh.

### X3. Stored evidence isn't encrypted

- **What:** home locations sit in Postgres as text.
- **Fix:** encryption at rest, and a retention policy.

## Operations

### O1. No schema migrations

- **What:** Hibernate's `ddl-auto: update` changes the schema with no history.
- **Fix:** Flyway.

### O2. Retry pause has no jitter

- **What:** the provider retry waits a fixed 20 ms.
- **Effect:** many threads could retry together.
- **Fix:** add a random pause.

### O3. Default secrets in `docker-compose.yml`

- **What:** the JWT secret and passwords have local defaults so the demo runs with one command.
- **Fix:** always set `JWT_SECRET`, `DB_PASSWORD` and `DEMO_PASSWORD` outside a local machine.

## Left out on purpose

| Not built | Why |
|---|---|
| A website or graphs | The case study doesn't ask for them |
| A real routing provider | Costs money, and makes failures hard to repeat |
| Road-accurate map matching | The case study defines its own rules |
| Multiple app instances | One is enough here, and Redis would add a server to run |
