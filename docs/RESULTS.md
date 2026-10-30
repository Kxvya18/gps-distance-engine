# Lab notebook

Thirteen experiments, each asking one question about the engine. Every number here was measured, and the raw data is in [results/](results/).

**Conditions:** Apple M2 with 8 cores (4 performance, 4 efficiency) and 16 GB RAM, Java 17, with other containers stopped. Each run used the same seed, so the same trips were replayed every time.

**Honest scope:** accuracy and cost come from simulated trips and a fake maps service. They show how the system behaves under controlled conditions, and they aren't a claim about real roads. See [LIMITATIONS.md](LIMITATIONS.md).

## Scorecard

| Question | Answer | Experiment |
|---|---|---|
| Is it accurate? | 1.26% average error, worst trip 2.73% | [E1](#e1-does-it-beat-the-two-simple-methods), [E2](#e2-how-spread-out-are-the-errors) |
| Does it fix the problem the case study describes? | Yes, both failures were reproduced and fixed | [E1](#e1-does-it-beat-the-two-simple-methods) |
| Does it save money? | 9.82 provider calls per trip down to 0.21 | [E4](#e4-how-much-do-provider-calls-fall) |
| Is it fast? | 10,000 trips in 7.6 s, 15M pings, 154 MB heap | [E7](#e7-does-it-hold-up-as-volume-grows) |
| Does the km ever change between runs? | Never: 0 of 10,000 trips differed | [E11](#e11-can-the-km-ever-change) |
| Does an outage change the bill? | No: 3,808 degraded trips recovered to the exact metres | [E10](#e10-what-does-an-outage-do) |
| Does it work over real HTTP and Postgres? | 2,000 trips, 0 errors, p99 94 ms | [E13](#e13-how-does-the-real-api-behave) |
| How good are the tests? | See [TESTS.md](TESTS.md) | |

---

## Accuracy

### E1. Does it beat the two simple methods?

**Question:** The case study says adding up pings under-pays, and asking the maps service about every gap over-pays when data is noisy. Are those true, and does the engine fix both?

**Method:** 10,000 trips, measured against the simulator's true distance, in two conditions: GPS that only goes missing, and GPS that also sends false fixes.

![Mean signed error for three methods in two conditions](charts/accuracy-two-worlds.svg)

| Method | GPS only goes missing | GPS goes missing and lies |
|---|---:|---:|
| Sum of pings | **-7.74%** | +5.98% |
| Route every gap | -1.32% | **+13.27%** |
| This engine | -1.25% | -1.27% |

Average absolute error, including the worst trips:

| Method, noisy GPS | Mean | p50 | p90 | p99 | Worst trip |
|---|---:|---:|---:|---:|---:|
| Sum of pings | 7.57% | 4.80% | 11.02% | 58.98% | 65.75% |
| Route every gap | 13.27% | 10.72% | 15.28% | 63.98% | 70.41% |
| This engine | 1.27% | 1.27% | 1.51% | 1.72% | 2.73% |

**What it means:** Both claims in the case study are confirmed. The sum of pings under-pays by 7.7% when dots go missing, and routing every gap over-pays by 13.3% when dots lie. The engine stays within 1.3% in both, so false data barely affects it. Its worst trip is better than the sum of pings' typical trip.

### E2. How spread out are the errors?

**Question:** An average can hide bad trips. Are any trips far off?

![Histogram of engine error across 10,000 trips](charts/error-histogram.svg)

| Range of signed error | Trips | Share |
|---|---:|---:|
| -2.75% to -2.00% | 10 | 0.1% |
| -2.00% to -1.50% | 1,038 | 10.4% |
| -1.50% to -1.00% | 8,066 | 80.7% |
| -1.00% to -0.25% | 886 | 8.9% |

**What it means:** Results sit in a tight band, and no trip escapes it. The band sits slightly below zero, so the engine reads a little low on every trip. The likely cause is straight lines cutting street corners, but that wasn't measured separately.

### E3. Does a bigger gap hurt?

**Question:** Trips lose different amounts of GPS. Does accuracy fall as the gap grows?

![Error by share of the trip with no GPS](charts/accuracy-by-gap.svg)

| Share of trip with no GPS | Trips | Sum of pings | This engine |
|---|---:|---:|---:|
| 0 to 15% | 2,598 | +11.39% | -1.27% |
| 15 to 25% | 3,367 | +7.52% | -1.24% |
| 25 to 35% | 2,334 | +3.22% | -1.29% |
| 35% or more | 1,701 | -1.51% | -1.28% |

By trip direction, the engine is just as steady:

| Direction | Trips | Mean absolute error | Worst trip |
|---|---:|---:|---:|
| LOGIN | 5,903 | 1.26% | 1.95% |
| LOGOUT | 4,097 | 1.27% | 2.73% |

**What it means:** The sum of pings swings by 13 points depending on the gap, because false fixes push it up and gaps pull it down. The two effects only appear to cancel. The engine doesn't move, so a trip with a huge gap gets the same quality as one with almost none.

---

## Cost

### E4. How much do provider calls fall?

**Question:** Routing calls are the main cost. What does each step save?

![Provider calls per trip for four approaches](charts/provider-calls.svg)

| Approach | Total calls | Per trip | Against the first row |
|---|---:|---:|---:|
| Route every raw gap | 98,237 | 9.82 | |
| One call per real gap | 20,594 | 2.06 | -79.0% |
| Batched per trip | 10,000 | 1.00 | -89.8% |
| Batched and cached | 2,104 | 0.21 | **-97.9%** |

**What it means:** Each step does a different job. Cleaning and anchors remove false gaps, batching groups the real ones, and the cache reuses what was already bought. Together they cut the bill by about 98%. The cache's share depends on the simulator repeating 300 corridors.

### E5. Where do the kilometres come from?

**Question:** How much of the billed distance needs a paid call?

![Share of segments and kilometres by source](charts/rule-mix.svg)

| Rule | Segments | Kilometres | Share of km |
|---|---:|---:|---:|
| `SHORT_GAP_AERIAL` | 14,618,863 | 266,881 | 76.58% |
| `ROUTED` | 20,594 | 81,576 | 23.41% |
| `PARKED` | 989 | 45 | 0.01% |

**What it means:** Only 0.14% of segments need the provider, yet they carry 23.4% of the km. That's the case for spending the budget exactly there and nowhere else.

### E6. How big should the cache be?

**Question:** What cache size is enough?

![Cache hit rate by cache size](charts/cache-size.svg)

| Cache entries | Provider calls | Hit rate |
|---:|---:|---:|
| 0 | 10,000 | 0.00% |
| 100 | 10,000 | 0.00% |
| 1,000 | 3,624 | 76.36% |
| 5,000 | 2,104 | 86.02% |
| 20,000 | 2,104 | 86.02% |
| 200,000 | 2,104 | 86.02% |

**What it means:** The hit rate flattens at about 5,000 entries for this workload, so the 200,000 limit is generous. Real traffic will have more distinct routes, so a large limit is cheap insurance. At 100 entries the cache is smaller than one pass over the corridors, so every entry is evicted before reuse.

---

## Speed

### E7. Does it hold up as volume grows?

**Question:** Do time and memory grow in step with trips?

| Trips | Pings, approx. | Run time | Trips per second | Peak heap |
|---:|---:|---:|---:|---:|
| 1,000 | 1.5M | 1.94 s | 516 | 190 MB |
| 2,500 | 3.8M | 2.89 s | 865 | 192 MB |
| 5,000 | 7.5M | 5.22 s | 958 | 191 MB |
| 10,000 | 15M | 7.71 s | 1,297 | 193 MB |
| 20,000 | 30M | 10.31 s | 1,939 | 198 MB |

Run times include generating the fake trips.

**What it means:** Memory stays flat, because trips are handled one at a time and released. Throughput rises with volume because the cache warms up, so later trips rarely wait for the provider. The main benchmark, in [benchmark-results.txt](benchmark-results.txt), gave 10,000 trips in 7.6 s at 1,310 trips per second with a 154 MB peak.

### E8. Do more cores help?

**Question:** Trips are independent, so do they spread across cores?

![Speed-up as threads increase](charts/thread-scaling.svg)

| Threads | Run time | Speed-up |
|---:|---:|---:|
| 1 | 3.78 s | 1.00× |
| 2 | 1.93 s | 1.96× |
| 4 | 0.99 s | 3.82× |
| 8 | 0.73 s | 5.21× |

**What it means:** Scaling is close to perfect up to 4 threads. The M2 has only 4 performance cores, and the other 4 are slower, which explains the drop at 8.

### E9. How much does a slow provider hurt?

**Question:** What happens as each provider call slows down?

![Run time against provider delay, with and without cache](charts/provider-latency.svg)

| Delay per call | With cache | Without cache | Cache saves |
|---:|---:|---:|---:|
| 0 ms | 0.93 s | 0.96 s | 3% |
| 5 ms | 1.86 s | 4.64 s | 60% |
| 20 ms | 5.22 s | 16.55 s | 68% |
| 50 ms | 11.02 s | 36.59 s | 70% |
| 100 ms | 20.64 s | 68.15 s | 70% |
| 200 ms | 39.53 s | 130.76 s | 70% |

These are 5,000 trips on 8 threads.

**What it means:** With an instant provider the cache makes no difference. As the provider slows, run time is decided almost entirely by waiting. This backs the design: speed comes from avoiding calls, not from faster code.

---

## Reliability

### E10. What does an outage do?

**Question:** If the provider dies mid-run, who is affected, how wrong are they, and do they recover exactly?

**Method:** 10,000 trips, with the provider down between 3,000 and 6,000 finished trips.

![Degraded trips across a provider outage](charts/outage-timeline.svg)

| Measure | Result |
|---|---:|
| Trips that finished | 10,000 of 10,000 |
| Trips marked `DEGRADED` | 3,808 |
| Average error of the × 1.4 estimate | +0.26% |
| Range of the estimate's error | -0.64% to +2.47% |
| Audit failures | 0 |
| Trips different from the healthy run after recompute | **0** |

**Reading the chart:**

- **The start is gradual.** Early in the outage, many trips still complete normally, because their routes are already cached. The cache acts as a shield.
- **The end is late.** Degraded trips continue for about 2,000 trips after the provider returns. The most likely reason is the circuit breaker's 1-second cool-down in this test, because trips run very fast when they aren't waiting for a provider.

**What it means:** An outage slows billing, but it can't change it. No trip failed, and every estimate was replaced by the exact figure.

### E11. Can the km ever change?

**Question:** The case study says two runs that disagree by 0.1 km are a billing bug. Does anything change the result?

| Condition, 10,000 trips | Total metres |
|---|---:|
| 8 threads with cache | 348,502,206 |
| 8 threads without cache | 348,502,206 |
| 1 thread with cache | 348,502,206 |
| 8 threads with a tiny cache | 348,502,206 |

**What it means:** Thread count, timing and cache state all change, and the total stays the same to the metre. The main benchmark separately compares every trip between two runs, and 0 differ.

---

## Limits

### E12. How does cleaning cost grow?

**Question:** The teleport filter is O(n²) in the worst case. How bad is that in practice?

![Cleaning time as trips grow, normal and pure noise](charts/complexity.svg)

| Pings | Normal trip | All noise |
|---:|---:|---:|
| 500 | 0.08 ms | 5.6 ms |
| 1,000 | 0.08 ms | 19.7 ms |
| 2,000 | 0.18 ms | 57.9 ms |
| 4,000 | 0.27 ms | 143.6 ms |
| 8,000 | 0.57 ms | 329.3 ms |
| 16,000 | 1.13 ms | 706.1 ms |

**What it means:** A normal trip grows in a straight line and costs about a tenth of a millisecond at the 1,500 pings the case study uses. Even a 16,000-ping trip of pure noise takes under a second. In this test the noise curve grew more slowly than n², because old chains eventually accept later pings as time passes. O(n²) remains the upper bound.

### E13. How does the real API behave?

**Question:** The experiments above test the engine. What about real HTTP, real JSON, real login and real Postgres writes?

**Method:** `ApiLoadRunner` against the Docker stack, 2,000 requests per operation, 8 at a time, each sending the 1,215-ping sample trip.

![API latency by operation](charts/api-load.svg)

| Operation | Requests per second | p50 | p90 | p95 | p99 | Max | Errors |
|---|---:|---:|---:|---:|---:|---:|---:|
| New trip | 194 | 35.5 ms | 61.1 ms | 71.6 ms | 94.0 ms | 487.0 ms | 0 |
| Replay of the same trip | 318 | 22.9 ms | 36.2 ms | 41.4 ms | 56.7 ms | 138.1 ms | 0 |
| Read a trip | 449 | 16.6 ms | 24.8 ms | 29.1 ms | 40.5 ms | 67.1 ms | 0 |

| Resource | Measured |
|---|---:|
| Storage for 2,000 trips | 210 MB, about 105 KB per trip |
| App container memory after the test | 910 MB |
| Database container memory after the test | 243 MB |

**What it means:** Over real HTTP the bottleneck moves to JSON and Postgres, at 194 new trips per second, against 1,310 in the engine alone. That is still about 17 million trips a day, far above this workload. Replays are faster because they skip the engine, which confirms that the evidence hash works. The sample trip's routes were cached after the first request, so this measures the system without provider delay.

---

## Reproduce it

Everything regenerates from the repo, apart from the chart images, which are drawn from the CSV files.

```bash
./mvnw -q -DskipTests compile
java -Xmx4g -cp target/classes com.moveinsync.distance.benchmark.ExperimentRunner
java -Xmx2g -cp target/classes com.moveinsync.distance.benchmark.BenchmarkRunner 10000 20
```

For E13, start the stack first:

```bash
docker compose up -d --build
java -cp target/classes com.moveinsync.distance.benchmark.ApiLoadRunner http://localhost:8080 2000 8
```

The `ExperimentRunner` run takes about 7 minutes.
