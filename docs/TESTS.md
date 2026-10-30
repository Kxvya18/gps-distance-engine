# Proof catalogue

Every test in the project, what it proves, and how good the tests themselves are. All 137 tests pass, and they run in about 25 seconds with `./mvnw test`.

## The three questions

| Question | Measure | Result |
|---|---|---|
| Do the tests pass? | Automated tests | **137 of 137** |
| Do they reach the code? | Line and branch coverage | **95.6%** of lines, **93.8%** of branches |
| Would they notice a bug? | Mutation score | **86.0%** of deliberate bugs caught |

Coverage only shows that a line ran. Mutation testing goes further: it plants small bugs on purpose, such as turning `<` into `<=`, and checks whether any test fails. So it tests the tests.

## What kinds of test exist

The instructions ask for unit, concurrency, failure and benchmark tests on normal inputs and edge cases.

| Kind | Tests | Where |
|---|---:|---|
| Unit tests with no server or database | 103 | `core`, `audit`, `routing` |
| Failure injection | 9 | `ProviderFailureTest`, `AutomaticRecoveryTest`, `DatabaseDownTest`, plus six timeout, retry, rate-limit and partial-failure cases in `ResilientRouterTest` |
| Concurrency | 5 | `ConcurrencyTest`, plus three in `RecomputeServiceTest` |
| Full application, HTTP and database layers | 30 | `TenantIsolationTest`, `RecomputeServiceTest`, `LogPrivacyTest`, and others |
| Benchmark | 1 | `BenchmarkTest`, with the full run in [RESULTS.md](RESULTS.md) |

Some tests count in more than one row.

## Coverage

![Line coverage by package](charts/coverage.svg)

| Package | Lines | Line coverage | Branches | Branch coverage |
|---|---:|---:|---:|---:|
| `model` | 27 of 27 | 100.0% | 0 of 0 | n/a |
| `simulation` | 143 of 143 | 100.0% | 91 of 94 | 96.8% |
| `dto` | 25 of 25 | 100.0% | 12 of 12 | 100.0% |
| `audit` | 69 | 55 | 14 | 79.7% |
| `core` | 145 | 133 | 12 | 91.7% |
| `model` | 5 | 5 | 0 | 100.0% |
| `routing` | 95 | 77 | 18 | 81.1% |
| **Total** | **314** | **270** | **44** | **86.0%** |

This covers the plain Java engine, using PIT. Spring-based tests weren't part of this run.

### What it found

The first run scored **78.3%**, and that was useful. It showed that the auditor's checks for the rejected and estimate rules were barely tested: deleting those checks went unnoticed. That's the kind of gap coverage alone wouldn't show, so 27 tests were added across the auditor, evidence hash, maths, anchors and router, and the score reached 86.0%.

### What it still misses

| Missed | Count | Why |
|---|---:|---|
| `<` changed to `<=`, mostly on decimal numbers | 21 | Two doubles rarely land exactly equal, so most of these make no practical difference |
| Simple getters used by metrics and tests | 6 | Used by Spring tests, which this run left out |
| Everything else | 17 | Mainly retry pauses, thread set-up and rarely used error paths. These were reviewed by category, not one by one |

## Bugs the tests found during the build

| Bug | Found by | Fix |
|---|---|---|
| Two late-ping updates could lose pings | Concurrency test | Compare-and-set on the evidence hash |
| Same timestamp gave an infinite speed | Simulator and audit | A jump within GPS error counts as plausible |
| Two closes at once returned a 500 | Concurrency test | Row lock |
| Auditor rules barely tested | Mutation testing | 27 new tests across the engine |

The test suite also caught several wrong expected values in tests themselves, which were corrected after checking by hand.

## Edge cases covered

| Area | Cases |
|---|---|
| Pings | Empty trip, duplicates, reversed order, bad first ping, bad burst mid-trip, accuracy exactly at the limit |
| Anchors | Missing coordinates, missing time, 0,0, off the map, exactly at the map's edge, no fallback available |
| Office | Just inside and outside 400 m, invalid trip end, logout trips |
| Rules | Exactly 70 s, exactly 100 km, either side of 5 km/h and 120 km/h, same timestamp |
| Provider | Down, hanging, rate limited, one batch failing, failing once then recovering |
| Invoices | Double close, close during late pings, repeated late pings, flagged trips, degraded trips |
| Security | Forged tenant, missing token, wrong password, wrong role, same trip id across clients |

## Every test

### The case study's own examples (2 tests)

**`PdfExamplesTest`** · The exact numbers from the case study · 2 tests

- Burst of pings 9 km away for 4 seconds is dropped and distance is unchanged
- Thirty one km logout trip with a 22 minute gap reports 30 point 6 km

### Clean the evidence (13 tests)

**`PingCleanerTest`** · Duplicates, order, accuracy, teleports · 8 tests

- Teleport limit comes from configuration
- Bad first ping does not lose the trip
- Empty input gives empty output
- Drops burst of bad pings from the middle
- Accuracy at the limit is kept and just worse is dropped
- Removes duplicates
- Sorts by time
- Drops low accuracy ping

**`GeoMathTest`** · Distance and speed maths · 5 tests

- No time and no movement is zero speed
- Nine km in four seconds is about 8100 kmh
- Same point is zero
- Movement in no time is infinite speed
- One degree of latitude is about 111 km

### Patch the gaps (18 tests)

**`AnchorBuilderTest`** · Fallback order, no-show flag, the 400 m rule · 18 tests

- Logout trip anchors the office at the start not the end
- Points come back in time order
- Invalid trip end uses office with trip end time
- Locations just off the map are invalid
- No show location is added when flag is on
- No show is skipped when flag is off
- Locations at the edge of the map are valid
- Rider with no valid point and no fallback adds nothing
- Zero latitude alone is still valid
- Missing time makes the actual point invalid
- Uses actual drop when valid
- Trip end beyond 400 meters uses office with trip end time
- Zero zero location is invalid
- Invalid no show location falls back to planned location
- Ad hoc rider falls back to profile home and sign in time
- Out of range location is invalid
- Trip end within 400 meters uses actual point
- Planned rider falls back to planned location and time

### Measure and bill (29 tests)

**`SegmentRuleEngineTest`** · Each rule and its boundaries · 14 tests

- Long gap under five kmh is parked
- Too fast route with plausible estimate is kept
- Same timestamp within gps error is ashort gap not aroute
- Too fast route with too fast estimate is rejected and uses aerial
- Short gap is plausible up to 120 kmh
- Short gap at plausible speed is aerial
- Short gap at impossible speed needs aroute
- Exactly 70 seconds is not ashort gap
- Parked needs aspeed under 5 kmh
- No answer uses straight line times 1 point 4
- Route of exactly 100 km is accepted
- Good route is used
- Route over 100 km is rejected and estimated
- Long moving gap needs aroute

**`DistanceCalculatorTest`** · Whole trips and determinism · 9 tests

- Full gps trip is all short gaps
- Shuffled and duplicated input gives the same segments
- Two separate runs agree to the metre whether or not answers were cached
- Burst of bad pings leaves the distance unchanged
- Partial router answer keeps the real routes and marks the trip degraded
- Total is the exact sum of the segments
- Trip with no usable points goes to review
- Anchors split the tunnel gap and only real gaps are routed
- No router answer marks the trip degraded

**`TripResultTest`** · The single rounding step · 1 tests

- Rounds half up to one decimal

**`EvidenceHasherTest`** · Recognising the same input · 5 tests

- Hash is afull sha 256 value
- Planned stops and riders change the hash
- Rule version changes the hash
- An extra ping changes the hash
- Order and duplicates do not change the hash

### Survive the provider (23 tests)

**`ResilientRouterTest`** · Cache, batching, timeouts, retries, rate limits · 13 tests

- Failed call is retried once then gives no answer
- When one batch fails the other batches still answer
- Points in the same cell share an answer
- A failed call succeeds on its retry
- Large request list is split into batches
- Rate limited calls are treated as failures not crashes
- Open breaker means no provider calls
- Second ask comes from the cache
- Timeout frees the call slot
- Duplicate requests in one list are asked once
- Hanging provider times out and gives no answer
- Repeated timeouts open the breaker
- A successful call is not repeated

**`RouteCacheTest`** · Eviction and expiry · 3 tests

- Returns what was saved
- Evicts the least recently used entry
- Expires after the ttl

**`CircuitBreakerTest`** · Open, test, close · 5 tests

- A success resets the failure count
- Successful test call closes the breaker
- Allows only one test call after the cool down
- Opens after the threshold
- Failed test call reopens the breaker

**`ProviderFailureTest`** · Failure injection: the provider dies halfway · 1 tests

- Trips are degraded during the outage and recover to the exact healthy km

**`AutomaticRecoveryTest`** · Failure injection: recovery with the real scheduler · 1 tests

- Degraded trip is recomputed without any request

### Keep invoices right (31 tests)

**`RecomputeServiceTest`** · Late pings, corrections, replays, races · 11 tests

- Closing twice gives the same invoice
- Concurrent late pings are all kept and corrections add up
- Many closes at once all get the same invoice
- Degraded trip cannot be billed and recovers automatically
- Replaying the same trip returns the same result without provider calls
- Late pings after billing make asigned correction and leave the invoice alone
- Signed delta is shown with its sign
- Replaying the same late pings changes nothing
- Rejected routes are counted
- Flagged trip holds its correction until aperson reviews it
- Closing while late pings arrive never loses the correction

**`TripAuditorTest`** · The second opinion catches tampering · 20 tests

- Catches awrong estimate
- Catches aroute that should have been parked
- Correct segments of every rule pass
- Catches awrong rule label
- Catches achanged short gap distance
- Catches aroute over the limit
- Catches awrong speed rejection distance
- Catches aspeed rejection when the estimate was fine
- Catches time running backwards
- Same place at the same instant is avalid short gap
- Catches atotal that is not the sum
- Catches awrong rejected distance
- Catches awrong short gap label
- Real engine results pass the audit
- Catches arejected route on an ok trip
- Catches segments that do not connect
- Catches an estimate on atrip not marked degraded
- Catches awrong parked distance
- Catches aroute shorter than the straight line
- Catches aparked segment that was moving

### Keep clients and people safe (15 tests)

**`TenantIsolationTest`** · Isolation, roles, safe errors · 13 tests

- Operator cannot switch the provider
- Employee cannot see someone elses trip
- Another client cannot read the trip
- No token and bad password are rejected
- Errors do not echo request data
- Employee cannot submit trips
- Two clients can use the same trip id safely
- Employee sees their own trip without coordinates
- Impossible ping gives ameaningful message
- Invalid fields are named without their values
- Too many late pings are rejected
- Another client cannot change or close the trip
- Metrics need arole

**`LogPrivacyTest`** · No personal data in logs · 1 tests

- Home location and rider id stay out of the logs(captured output)

**`DatabaseDownTest`** · Failure injection: database unreachable · 1 tests

- Caller gets aretryable error with no internal details

### Hold under pressure (3 tests)

**`ConcurrencyTest`** · Many threads at once · 2 tests

- Breaker opens once under concurrent failures
- Many threads get the same answers and keep the cache bounded

**`BenchmarkTest`** · A small benchmark on every build · 1 tests

- Small run finishes with no audit failures and agreeing results(captured output)

### Start up correctly (3 tests)

**`RuleConfigurationTest`** · Limits come from configuration · 2 tests

- Other limits keep the spec defaults
- Configuration overrides the defaults

**`GpsDistanceEngineApplicationTests`** · The whole app starts · 1 tests

- Context loads

## What isn't automated

- `kill -9` and stopping Postgres on Docker were manual checks.
- Automated tests use H2, not Postgres. The HTTP load test and demo ran on real Postgres.

See [LIMITATIONS.md](LIMITATIONS.md).

## Run them yourself

```bash
./mvnw test
```

Coverage report, written to `target/site/jacoco/index.html`, comes with that run. Mutation testing takes about a minute:

```bash
./mvnw test-compile org.pitest:pitest-maven:mutationCoverage
```
