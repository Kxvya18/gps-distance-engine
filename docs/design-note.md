# Design note

The one-to-two page summary. Every decision is argued in full in the [design dossier](DESIGN.md).

## The problem

Vendors are paid per km, and GPS loses data. The engine rebuilds each trip's distance from pings and known stops, using routing calls only for real gaps, and explains every km.

## Main components

| Component | Responsibility |
|---|---|
| `PingCleaner` | Removes duplicates, sorts, drops weak fixes and teleports |
| `AnchorBuilder` | Adds employee, no-show and office anchors with their fallbacks |
| `SegmentRuleEngine` | Picks the rule and distance for each pair of points |
| `DistanceCalculator` | Runs the pipeline for one trip |
| `ResilientRouter` | Cache, batching, timeout, retry and circuit breaker around the provider |
| `TripAuditor` | Separately rechecks every result before billing |
| `TripService`, `TripStore` | Compute outside a transaction, then save atomically |
| `RecomputeService` | Retries degraded trips on a schedule |

## Architecture

```mermaid
flowchart LR
    Client -->|JWT| API[TripController]
    API --> Service[TripService]
    Service --> Engine[DistanceCalculator<br/>plain Java]
    Engine --> Cleaner[PingCleaner]
    Engine --> Anchors[AnchorBuilder]
    Engine --> Rules[SegmentRuleEngine]
    Engine --> Router[ResilientRouter]
    Router --> Cache[RouteCache<br/>LRU + TTL]
    Router --> Breaker[CircuitBreaker]
    Router --> Provider[RoutingProvider]
    Service --> Auditor[TripAuditor<br/>separate code]
    Service --> Store[TripStore]
    Store --> DB[(Postgres)]
    Recompute[RecomputeService<br/>scheduled] --> Service
```

The engine has no Spring dependency, so it runs in tests and in the benchmark with no server or database.

## Data model

```mermaid
erDiagram
    TRIP_RECORD ||--o| INVOICE : "billed as"
    INVOICE ||--o{ INVOICE_CORRECTION : "corrected by"
    TRIP_RECORD {
        string tenant_id "unique with trip_id"
        string trip_id
        int rule_version
        string evidence_hash "detects replays"
        long total_meters
        string status "OK, DEGRADED, NEEDS_REVIEW"
        int provider_calls
        timestamp degraded_since
        text evidence_json "input for recompute"
        text result_json "segments and labels"
        long version "optimistic lock"
    }
    INVOICE {
        string tenant_id "unique with trip_id"
        string trip_id
        long billed_meters "never changes"
        int rule_version
        timestamp closed_at
    }
    INVOICE_CORRECTION {
        string tenant_id
        string trip_id
        long delta_meters "signed"
        string evidence_hash "one correction per hash"
        timestamp created_at
    }
```

Trip input is pings, milestones, planned stops, ad-hoc riders and the office location. Distances are whole metres in a `long`.

## APIs

| Endpoint | Purpose |
|---|---|
| `POST /api/auth/login` | Get a signed token carrying the tenant and role |
| `POST /api/trips` | Submit evidence and get the labelled result |
| `GET /api/trips/{id}` | Read a result |
| `POST /api/trips/{id}/late-pings` | Add late pings and recompute |
| `POST /api/trips/{id}/close` | Close the invoice, with `?reviewed=true` for flagged trips |
| `GET /api/trips/{id}/invoice` | Read the invoice and its corrections |

## Hardest flow: late pings after billing

```mermaid
sequenceDiagram
    participant V as Caller
    participant S as TripService
    participant E as Engine and auditor
    participant T as TripStore
    participant D as Postgres
    V->>S: POST late-pings
    S->>T: read trip and evidence hash H1
    S->>S: merge pings, compute new hash H2
    alt H2 equals H1
        S-->>V: same result, nothing changed
    else evidence changed
        S->>E: compute and audit, outside any transaction
        S->>T: save(result, H2, expected H1)
        T->>D: begin, lock the trip row
        alt stored hash is not H1
            T-->>S: refused
            S->>S: wait a random moment, start again from fresh data
        else still H1
            T->>D: update trip, and if OK and billed, insert correction (new km - km billed so far)
            D-->>T: commit
            T-->>S: saved
            S-->>V: new result
        end
    end
```

The provider call must not sit inside a database transaction, so computing happens outside one. That creates a read-compute-write race, which the concurrency test exposed: two updates could read the same trip and the second save dropped the first one's pings. The expected-hash check closes it. The save and the close also lock the trip row, so they take turns, and a late update always sees an invoice closed before it.

## Consistency model

A trip is always answered, even with the provider down. That choice favours availability. Billing favours consistency: an estimate can never reach an invoice, and an invoice is never changed. The two meet at `DEGRADED`, which is visible, blocks billing, and resolves automatically.

Latency is traded against accuracy in one place. A slow provider is given 2 seconds per call, then one retry, so a request stays bounded instead of waiting forever. After that the caller gets an estimate straight away, and accuracy returns later through the recompute. Cached routes are the fast path, at the cost of answers up to 24 hours old.

## Failure modes

| What fails | What the user sees | How the system recovers |
|---|---|---|
| Provider down or timing out | Trip returns `DEGRADED` with estimated segments, and closing it gives 409 | Each call times out after 2 seconds. One retry, then the breaker opens. `RecomputeService` recomputes within seconds of recovery |
| One batch fails, others succeed | Trip keeps its real routes, the failed gaps are estimated, and it is `DEGRADED` | Recomputed like any degraded trip |
| Provider rate limit | Same as above | Concurrent calls are capped by a semaphore, and the breaker backs off |
| App killed mid-work | Requests fail until restart | Evidence and status are in Postgres, so degraded trips are picked up after restart. Tested with `kill -9` |
| Crash between result and correction | Nothing partial | Both commit in one transaction |
| Duplicate or replayed feed | Same result returned | Evidence hash match, with no provider calls |
| Out-of-order or duplicate pings | Same km | Sorted and deduplicated before any rule runs |
| Bad fix burst, including at trip start | Those pings are dropped | Longest plausible chain |
| Concurrent updates to one trip | Both succeed | Compare-and-set with retry, or 409 after 10 attempts |
| Close at the same moment as a late-ping update | Both succeed, and the correction is kept | The trip row is locked during save and close, so they take turns |
| Auditor disagrees, or a route is rejected | `NEEDS_REVIEW` | Blocked from billing, and any correction is held, until closed with `reviewed=true` |
| Postgres down | 503, "Temporarily unavailable, safe to retry", within about 3 seconds | The app stays up and recovers when Postgres returns. Retrying the same request is safe. Tested by stopping Postgres |

## Trade-offs

- **Snapped cells cost accuracy for determinism.** The provider is asked about cell centres (0.0005°, about 55 m), so a cached answer equals a fresh one. Each endpoint can move by up to about 40 m.
- **Evidence is stored as JSON in the trip row.** It is simple and makes recompute easy, at about 105 KB per trip.
- **The cache and breaker live in one JVM.** Threads share them cheaply, and losing them on restart costs only some extra provider calls.
- **The auditor is separate code, not a separate team.** It has its own formula and shares no engine code, but a misreading of the written rules would pass both. The simulator's true distances are a second check.

## Left out on purpose

A real routing provider, running several app instances, schema migrations, login rate limiting, encryption of stored evidence, and road-accurate map matching. With a real provider, answers can change over time, so a recompute after late pings could also shift by that drift. Storing each trip's route answers would fix this.
