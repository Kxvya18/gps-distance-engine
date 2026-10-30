package com.moveinsync.distance.benchmark;

import com.moveinsync.distance.audit.TripAuditor;
import com.moveinsync.distance.core.DistanceCalculator;
import com.moveinsync.distance.core.GeoMath;
import com.moveinsync.distance.model.Ping;
import com.moveinsync.distance.model.RuleConfig;
import com.moveinsync.distance.model.Segment;
import com.moveinsync.distance.model.SegmentRule;
import com.moveinsync.distance.model.TripResult;
import com.moveinsync.distance.routing.CircuitBreaker;
import com.moveinsync.distance.routing.FakeRoutingProvider;
import com.moveinsync.distance.routing.ResilientRouter;
import com.moveinsync.distance.routing.RouteCache;
import com.moveinsync.distance.simulation.SimulatedTrip;
import com.moveinsync.distance.simulation.TripSimulator;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

// Runs synthetic trips through the engine and prints time, memory, calls and accuracy.
// Usage: java -cp target/classes com.moveinsync.distance.benchmark.BenchmarkRunner [trips] [providerLatencyMillis]
public class BenchmarkRunner {

    private static final long SEED = 42;
    private static final int THREADS = Runtime.getRuntime().availableProcessors();

    private record TripOutcome(long trueMeters, long naiveMeters, long resultMeters, int pings,
                               long engineNanos, int providerCalls, int routedGaps,
                               TripResult.Status status, int auditProblems) {
    }

    private record RunResult(List<TripOutcome> outcomes, double seconds, long peakHeapBytes) {
    }

    public static void main(String[] args) throws Exception {
        int trips = args.length > 0 ? Integer.parseInt(args[0]) : 10_000;
        long latency = args.length > 1 ? Long.parseLong(args[1]) : 20;

        System.out.println("Trips: " + trips + ", threads: " + THREADS + ", fake provider latency: " + latency + " ms");

        RunResult withCache = run(trips, latency, 200_000, -1);
        report("Cache and batching", withCache);

        RunResult noCache = run(trips, latency, 0, -1);
        report("Batching only, no cache", noCache);

        int gaps = withCache.outcomes().stream().mapToInt(TripOutcome::routedGaps).sum();
        long callsCached = withCache.outcomes().stream().mapToLong(TripOutcome::providerCalls).sum();
        long callsBatched = noCache.outcomes().stream().mapToLong(TripOutcome::providerCalls).sum();
        System.out.printf("Provider calls: %d gaps one call each -> %d batched -> %d batched and cached%n", gaps, callsBatched, callsCached);

        printAccuracy(withCache.outcomes());
        failureRun(trips, latency, withCache.outcomes());
    }

    private static RunResult run(int trips, long latency, int cacheSize, int goDownAfter) throws Exception {
        FakeRoutingProvider provider = new FakeRoutingProvider(latency, THREADS * 2);
        return run(trips, provider, cacheSize, goDownAfter);
    }

    private static RunResult run(int trips, FakeRoutingProvider provider, int cacheSize, int goDownAfter) throws Exception {
        RuleConfig config = RuleConfig.defaults();
        RouteCache cache = new RouteCache(cacheSize, 86_400_000L, System::currentTimeMillis);
        CircuitBreaker breaker = new CircuitBreaker(5, 1000, System::currentTimeMillis);
        ResilientRouter router = new ResilientRouter(provider, cache, breaker, 25, THREADS);
        DistanceCalculator calculator = new DistanceCalculator(config, router);
        TripAuditor auditor = new TripAuditor(config);
        TripSimulator simulator = new TripSimulator(SEED);

        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        AtomicLong peakHeap = new AtomicLong();
        AtomicInteger finished = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<TripOutcome>> futures = new ArrayList<>();
        long start = System.nanoTime();
        for (int i = 0; i < trips; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                SimulatedTrip trip = simulator.generate(index);
                long before = System.nanoTime();
                TripResult result = calculator.calculate(trip.evidence());
                long nanos = System.nanoTime() - before;

                if (finished.incrementAndGet() == goDownAfter) {
                    provider.setDown(true);
                }
                peakHeap.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(), Math::max);
                return new TripOutcome(trip.trueMeters(), naiveMeters(trip.evidence().pings()), result.totalMeters(),
                        trip.evidence().pings().size(), nanos, result.providerCalls(), routedGaps(result),
                        result.status(), auditor.audit(result).size());
            }));
        }
        List<TripOutcome> outcomes = new ArrayList<>();
        for (Future<TripOutcome> future : futures) {
            outcomes.add(future.get());
        }
        pool.shutdown();
        return new RunResult(outcomes, (System.nanoTime() - start) / 1e9, peakHeap.get());
    }

    private static int routedGaps(TripResult result) {
        int count = 0;
        for (Segment segment : result.segments()) {
            if (segment.rule() != SegmentRule.SHORT_GAP_AERIAL && segment.rule() != SegmentRule.PARKED) {
                count++;
            }
        }
        return count;
    }

    // The naive answer from the problem statement: add up the pings. Sorting by time first
    // is a kindness to the baseline, so the comparison does not overstate our result.
    private static long naiveMeters(List<Ping> rawPings) {
        List<Ping> pings = new ArrayList<>(rawPings);
        pings.sort(Comparator.comparingLong(Ping::timestampMillis));
        double total = 0;
        for (int i = 0; i + 1 < pings.size(); i++) {
            Ping a = pings.get(i);
            Ping b = pings.get(i + 1);
            total += GeoMath.metersBetween(a.latitude(), a.longitude(), b.latitude(), b.longitude());
        }
        return Math.round(total);
    }

    private static void report(String name, RunResult run) {
        List<TripOutcome> outcomes = run.outcomes();
        long[] micros = outcomes.stream().mapToLong(o -> o.engineNanos() / 1000).sorted().toArray();
        long pings = outcomes.stream().mapToLong(TripOutcome::pings).sum();
        long audits = outcomes.stream().filter(o -> o.auditProblems() > 0).count();

        System.out.println();
        System.out.println("== " + name + " ==");
        System.out.printf("Wall time: %.1f s (includes generating the fake trips)%n", run.seconds());
        System.out.printf("Trips per second: %.0f%n", outcomes.size() / run.seconds());
        System.out.printf("Pings: %d%n", pings);
        System.out.printf("Engine time per trip: p50 %.2f ms, p99 %.2f ms%n", percentile(micros, 50) / 1000.0, percentile(micros, 99) / 1000.0);
        System.out.printf("Peak heap seen: %d MB%n", run.peakHeapBytes() / (1024 * 1024));
        System.out.println("Statuses: " + countStatuses(outcomes));
        System.out.println("Trips failing the audit: " + audits);
    }

    private static String countStatuses(List<TripOutcome> outcomes) {
        StringBuilder text = new StringBuilder();
        for (TripResult.Status status : TripResult.Status.values()) {
            long count = outcomes.stream().filter(o -> o.status() == status).count();
            text.append(status).append('=').append(count).append(' ');
        }
        return text.toString().trim();
    }

    private static long percentile(long[] sorted, int percent) {
        int position = (int) Math.ceil(percent / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, position)];
    }

    private static void printAccuracy(List<TripOutcome> outcomes) {
        double naiveError = 0;
        double engineError = 0;
        double naiveSigned = 0;
        double engineSigned = 0;
        double[] engineErrors = new double[outcomes.size()];
        for (int i = 0; i < outcomes.size(); i++) {
            TripOutcome o = outcomes.get(i);
            double naive = (o.naiveMeters() - o.trueMeters()) * 100.0 / o.trueMeters();
            double engine = (o.resultMeters() - o.trueMeters()) * 100.0 / o.trueMeters();
            naiveError += Math.abs(naive);
            engineError += Math.abs(engine);
            naiveSigned += naive;
            engineSigned += engine;
            engineErrors[i] = Math.abs(engine);
        }
        int n = outcomes.size();
        Arrays.sort(engineErrors);
        System.out.println();
        System.out.println("== Accuracy against the simulator's true distance ==");
        System.out.printf("Naive sum of raw pings: mean abs error %.2f%%, mean signed error %+.2f%%%n", naiveError / n, naiveSigned / n);
        System.out.printf("This engine: mean abs error %.2f%%, mean signed error %+.2f%%, worst trip %.2f%%%n",
                engineError / n, engineSigned / n, engineErrors[n - 1]);
    }

    // Provider dies when half the trips are done, then recovers. Degraded trips are recomputed
    // and must match the run where the provider never failed.
    private static void failureRun(int trips, long latency, List<TripOutcome> healthy) throws Exception {
        FakeRoutingProvider provider = new FakeRoutingProvider(latency, THREADS * 2);
        RunResult failed = run(trips, provider, 200_000, trips / 2);
        report("Provider dies halfway", failed);

        provider.setDown(false);
        RuleConfig config = RuleConfig.defaults();
        RouteCache cache = new RouteCache(200_000, 86_400_000L, System::currentTimeMillis);
        CircuitBreaker breaker = new CircuitBreaker(5, 1000, System::currentTimeMillis);
        DistanceCalculator calculator = new DistanceCalculator(config, new ResilientRouter(provider, cache, breaker, 25, THREADS));
        TripSimulator simulator = new TripSimulator(SEED);

        int degraded = 0;
        int stillDifferent = 0;
        for (int i = 0; i < trips; i++) {
            if (failed.outcomes().get(i).status() == TripResult.Status.DEGRADED) {
                degraded++;
                TripResult fixed = calculator.calculate(simulator.generate(i).evidence());
                if (fixed.totalMeters() != healthy.get(i).resultMeters()) {
                    stillDifferent++;
                }
            }
        }
        System.out.printf("Recomputed %d degraded trips after recovery. Different from the healthy run: %d%n", degraded, stillDifferent);
    }
}
