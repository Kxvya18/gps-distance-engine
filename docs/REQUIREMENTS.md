# Requirement to proof

A checklist of the whole case study. Each line of the brief, where it lives in the code, and what proves it.


### I. Ingest the trip evidence

| Requirement | Where | Proof |
|---|---|---|
| Inputs: pings, milestones, planned stops, office | `TripRequest`, `TripEvidence` | `scripts/sample-trip.json` |
| Remove duplicates, order by time | `PingCleaner` | `PingCleanerTest`, `DistanceCalculatorTest` |
| Drop low-accuracy fixes | `PingCleaner` | `PingCleanerTest` |
| Drop teleports over a configurable limit, default 120 km/h | `PingCleaner`, `app.rules` | `PingCleanerTest`, `RuleConfigurationTest` |

### II. Patch the gaps

| Requirement | Where | Proof |
|---|---|---|
| Employee anchor with defined fallback order | `AnchorBuilder` | `AnchorBuilderTest` |
| No-show anchor behind a flag, with planned fallback | `AnchorBuilder` | `AnchorBuilderTest`, `RuleConfigurationTest` |
| Office anchor with the 400 m rule | `AnchorBuilder` | `AnchorBuilderTest` |

### III. Distance for every segment

| Requirement | Where | Proof |
|---|---|---|
| Short gaps and parked gaps use straight line | `SegmentRuleEngine` | `SegmentRuleEngineTest` |
| Real gaps use the provider | `DistanceCalculator` | `DistanceCalculatorTest` |
| Reject over 100 km, or too fast | `SegmentRuleEngine` | `SegmentRuleEngineTest` |
| Every segment carries its rule | `Segment`, `SegmentRule` | `PdfExamplesTest` |

### IV. Cost, failure and recomputation

| Requirement | Where | Proof |
|---|---|---|
| Cache by snapped cells, batch, report calls per trip | `ResilientRouter`, `RouteCache` | `ResilientRouterTest`, `RouteCacheTest` |
| Provider failure: trip circuit, × 1.4, `DEGRADED` | `CircuitBreaker`, `SegmentRuleEngine` | `CircuitBreakerTest`, `ProviderFailureTest` |
| Recompute automatically on recovery | `RecomputeService` | `AutomaticRecoveryTest` |
| Same input and rule version, same km | `EvidenceHasher`, `DistanceCalculator` | `EvidenceHasherTest`, `DistanceCalculatorTest`, `RecomputeServiceTest` |
| Late pings give a signed delta, invoice unchanged | `TripStore` | `RecomputeServiceTest` |

### Examples and key points

| Requirement | Proof |
|---|---|
| 31 km trip: naive 18.4, engine 30.6, two routed gaps | `PdfExamplesTest` |
| 9 km burst for 4 seconds is dropped, distance unchanged | `PdfExamplesTest` |
| Rule order deterministic and versioned | `SegmentRuleEngine`, `ruleVersion` on every result |
| Floating point and units, with rounding stated | [rulebook](rules.md), `TripResultTest` |
| Provider calls before and after caching | [E4](RESULTS.md#e4-how-much-do-provider-calls-fall) |
| 10,000 trips of about 1,500 pings, time and memory | [E7](RESULTS.md#e7-does-it-hold-up-as-volume-grows) |
| Provider fails halfway | `ProviderFailureTest`, [E10](RESULTS.md#e10-what-does-an-outage-do) |

### Plus points

| # | Point | Where it is covered |
|---|---|---|
| 1 | Authentication, roles, tenant isolation | [SECURITY.md](SECURITY.md), `TenantIsolationTest` |
| 2 | Time and space cost | [E7](RESULTS.md#e7-does-it-hold-up-as-volume-grows), [E12](RESULTS.md#e12-how-does-cleaning-cost-grow), benchmark |
| 3 | Failure handling and injection | [failure table](design-note.md#failure-modes), [OPERATIONS.md](OPERATIONS.md), `ProviderFailureTest` |
| 4 | Object-oriented design and layers | [DESIGN.md](DESIGN.md#2-architecture) |
| 5 | Trade-offs and what was left out | [DESIGN.md](DESIGN.md#7-decision-records), [LIMITATIONS.md](LIMITATIONS.md) |
| 6 | Metrics and structured logs | [OPERATIONS.md](OPERATIONS.md) |
| 7 | Caching and staleness | [D10, D11](DESIGN.md#d11-in-process-lru-cache), [E6](RESULTS.md#e6-how-big-should-the-cache-be) |
| 8 | Safe errors, no personal data | [SECURITY.md](SECURITY.md), `LogPrivacyTest` |

### Advanced Track

| Requirement | Where |
|---|---|
| Every hard rule tested, including concurrency and failure | [TESTS.md](TESTS.md) |
| Benchmark with throughput, p50 and p99, and the machine | [RESULTS.md](RESULTS.md) |
| Design note with architecture, sequence diagram and failure table | [design-note.md](design-note.md) |
| One-command run | `docker compose up --build` |

### Instructions

| Instruction | Where |
|---|---|
| Choose a language | Java with Spring Boot, the preferred stack. See [D1](DESIGN.md#d1-java-17-and-spring-boot) |
| Design the solution: data model, components, APIs, failure modes | [design-note.md](design-note.md), with full reasoning in [DESIGN.md](DESIGN.md) |
| Clean, layered code with the core in plain classes | [DESIGN.md](DESIGN.md#package-rules) |
| Unit, concurrency, failure and benchmark tests | [TESTS.md](TESTS.md) |
| README with how to run, assumptions, benchmark results and trade-offs | [README](../README.md) |
| Public repository with a meaningful commit history | This repository |

The design note was written alongside the build, so it records decisions as they were made, not a plan written beforehand.

## Where the case study was not followed literally

| Point | What the code does | Why |
|---|---|---|
| Office anchor on logout trips | Anchors the office at the trip start | The rule's wording contradicts the case study's own logout example. See [D25](DESIGN.md#d25-office-anchor-on-logout-trips) |

Open questions the case study leaves to the implementer are listed in the [rulebook](rules.md#decisions-where-the-spec-is-silent).
