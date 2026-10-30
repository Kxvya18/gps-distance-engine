package com.moveinsync.distance.benchmark;

import com.moveinsync.distance.audit.TripAuditor;
import com.moveinsync.distance.core.DistanceCalculator;
import com.moveinsync.distance.core.GeoMath;
import com.moveinsync.distance.core.PingCleaner;
import com.moveinsync.distance.model.Ping;
import com.moveinsync.distance.model.RuleConfig;
import com.moveinsync.distance.model.Segment;
import com.moveinsync.distance.model.SegmentRule;
import com.moveinsync.distance.model.TripEvidence;
import com.moveinsync.distance.model.TripResult;
import com.moveinsync.distance.routing.CircuitBreaker;
import com.moveinsync.distance.routing.FakeRoutingProvider;
import com.moveinsync.distance.routing.ResilientRouter;
import com.moveinsync.distance.routing.RouteCache;
import com.moveinsync.distance.routing.RouteRequest;
import com.moveinsync.distance.simulation.SimulatedTrip;
import com.moveinsync.distance.simulation.TripSimulator;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

// Runs the experiments behind docs/RESULTS.md and writes each one to docs/results as a CSV.
// Usage: java -Xmx4g -cp target/classes com.moveinsync.distance.benchmark.ExperimentRunner
public class ExperimentRunner {

    private static final long SEED = 42;
    private static final int CORES = Runtime.getRuntime().availableProcessors();
    private static final int FULL = 10_000;
    private static final Path OUT = Path.of("docs/results");
    private static final RuleConfig CONFIG = RuleConfig.defaults();

    // What one batch of trips should look like.
    private record Setup(int trips, int threads, long latencyMillis, int cacheEntries, boolean falseFixes,
                         int downAfter, int upAfter) {

        static Setup standard() {
            return new Setup(FULL, CORES, 20, 200_000, true, -1, -1);
        }
    }

    private record Row(int index, int finishOrder, TripEvidence.Direction direction, long trueMeters, long gapMeters,
                       long naiveMeters, long routeEveryGapMeters, int routeEveryGapCalls, long engineMeters, long nanos,
                       int providerCalls, int gaps, TripResult.Status status, int auditProblems) {
    }

    private record Batch(List<Row> rows, double seconds, long peakHeapBytes, long hits, long misses,
                         Map<SegmentRule, long[]> ruleTotals) {
    }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUT);
        warmUp();

        Batch standard = runBatch(Setup.standard());
        accuracy("accuracy-noisy-gps.csv", standard);
        accuracyByGap("accuracy-by-gap.csv", standard);
        accuracyByDirection(standard);
        errorHistogram(standard);
        ruleMix(standard);
        latency(standard);
        providerCost(standard);
        System.out.println("standard run done");

        accuracy("accuracy-missing-gps-only.csv", runBatch(new Setup(FULL, CORES, 0, 200_000, false, -1, -1)));
        System.out.println("missing-gps run done");

        scaleByTrips();
        scaleByThreads();
        providerLatency();
        cacheSize();
        System.out.println("scaling done");

        outage(standard);
        repeatability();
        complexity();
        System.out.println("all experiments done");
    }

    private static void warmUp() throws Exception {
        runBatch(new Setup(1_000, CORES, 0, 1_000, true, -1, -1));
    }

    private static Batch runBatch(Setup setup) throws Exception {
        return runBatch(setup, new FakeRoutingProvider(setup.latencyMillis(), Math.max(1, setup.threads() * 2)));
    }

    private static Batch runBatch(Setup setup, FakeRoutingProvider provider) throws Exception {
        RouteCache cache = new RouteCache(setup.cacheEntries(), 86_400_000L, System::currentTimeMillis);
        CircuitBreaker breaker = new CircuitBreaker(5, 1000, System::currentTimeMillis);
        ResilientRouter router = new ResilientRouter(provider, cache, breaker, 25, setup.threads());
        DistanceCalculator calculator = new DistanceCalculator(CONFIG, router);
        TripAuditor auditor = new TripAuditor(CONFIG);
        TripSimulator simulator = new TripSimulator(SEED, setup.falseFixes());
        FakeRoutingProvider freeProvider = new FakeRoutingProvider(0, 10_000);

        Map<SegmentRule, long[]> ruleTotals = new EnumMap<>(SegmentRule.class);
        Map<SegmentRule, LongAdder> ruleSegments = new ConcurrentHashMap<>();
        Map<SegmentRule, LongAdder> ruleMeters = new ConcurrentHashMap<>();
        AtomicInteger finished = new AtomicInteger();
        AtomicLong peakHeap = new AtomicLong();

        ExecutorService pool = Executors.newFixedThreadPool(setup.threads());
        List<Future<Row>> futures = new ArrayList<>();
        long start = System.nanoTime();
        for (int i = 0; i < setup.trips(); i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                SimulatedTrip trip = simulator.generate(index);
                long before = System.nanoTime();
                TripResult result = calculator.calculate(trip.evidence());
                long nanos = System.nanoTime() - before;

                int order = finished.incrementAndGet();
                if (order == setup.downAfter()) {
                    provider.setDown(true);
                }
                if (order == setup.upAfter()) {
                    provider.setDown(false);
                }
                int gaps = 0;
                for (Segment segment : result.segments()) {
                    ruleSegments.computeIfAbsent(segment.rule(), r -> new LongAdder()).increment();
                    ruleMeters.computeIfAbsent(segment.rule(), r -> new LongAdder()).add(segment.distanceMeters());
                    if (segment.rule() != SegmentRule.SHORT_GAP_AERIAL && segment.rule() != SegmentRule.PARKED) {
                        gaps++;
                    }
                }
                long[] routeEveryGap = routeEveryGap(trip.evidence().pings(), freeProvider);
                peakHeap.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), Math::max);
                return new Row(index, order, trip.evidence().direction(), trip.trueMeters(), trip.gapMeters(),
                        naive(trip.evidence().pings()), routeEveryGap[0], (int) routeEveryGap[1], result.totalMeters(),
                        nanos, result.providerCalls(), gaps, result.status(), auditor.audit(result).size());
            }));
        }
        List<Row> rows = new ArrayList<>();
        for (Future<Row> future : futures) {
            rows.add(future.get());
        }
        pool.shutdown();
        double seconds = (System.nanoTime() - start) / 1e9;
        for (SegmentRule rule : ruleSegments.keySet()) {
            ruleTotals.put(rule, new long[]{ruleSegments.get(rule).sum(), ruleMeters.get(rule).sum()});
        }
        return new Batch(rows, seconds, peakHeap.get(), cache.hitCount(), cache.missCount(), ruleTotals);
    }

    private static List<Ping> sortedByTime(List<Ping> pings) {
        List<Ping> sorted = new ArrayList<>(pings);
        sorted.sort(Comparator.comparingLong(Ping::timestampMillis));
        return sorted;
    }

    // The first naive answer from the problem statement: add up the pings.
    private static long naive(List<Ping> rawPings) {
        List<Ping> pings = sortedByTime(rawPings);
        double total = 0;
        for (int i = 0; i + 1 < pings.size(); i++) {
            total += aerial(pings.get(i), pings.get(i + 1));
        }
        return Math.round(total);
    }

    // The second naive answer: ask the maps service about every gap, with no cleaning or anchors.
    private static long[] routeEveryGap(List<Ping> rawPings, FakeRoutingProvider provider) {
        List<Ping> pings = sortedByTime(rawPings);
        long total = 0;
        long calls = 0;
        for (int i = 0; i + 1 < pings.size(); i++) {
            Ping a = pings.get(i);
            Ping b = pings.get(i + 1);
            double meters = aerial(a, b);
            long millis = b.timestampMillis() - a.timestampMillis();
            boolean gap = millis >= CONFIG.shortGapSeconds() * 1000 || GeoMath.speedKmh(meters, millis) > CONFIG.maxSpeedKmh();
            if (gap) {
                RouteRequest request = new RouteRequest(a.latitude(), a.longitude(), b.latitude(), b.longitude());
                total += provider.drivingMeters(List.of(request)).get(0);
                calls++;
            } else {
                total += Math.round(meters);
            }
        }
        return new long[]{total, calls};
    }

    private static double aerial(Ping a, Ping b) {
        return GeoMath.metersBetween(a.latitude(), a.longitude(), b.latitude(), b.longitude());
    }

    private static double percent(long measured, long truth) {
        return (measured - truth) * 100.0 / truth;
    }

    private static double[] sorted(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
    }

    private static double pick(double[] sorted, double percentile) {
        int position = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, position)];
    }

    private static String accuracyLine(String method, List<Double> signed) {
        List<Double> absolute = signed.stream().map(Math::abs).toList();
        double[] abs = sorted(absolute);
        double meanSigned = signed.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double meanAbs = absolute.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double[] sign = sorted(signed);
        return String.format("%s,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f", method, meanAbs, meanSigned,
                pick(abs, 50), pick(abs, 90), pick(abs, 99), abs[abs.length - 1], sign[0], sign[sign.length - 1]);
    }

    private static void accuracy(String file, Batch batch) throws IOException {
        List<Double> naive = new ArrayList<>();
        List<Double> routed = new ArrayList<>();
        List<Double> engine = new ArrayList<>();
        for (Row r : batch.rows()) {
            naive.add(percent(r.naiveMeters(), r.trueMeters()));
            routed.add(percent(r.routeEveryGapMeters(), r.trueMeters()));
            engine.add(percent(r.engineMeters(), r.trueMeters()));
        }
        write(file, "method,mean_abs_pct,mean_signed_pct,p50_abs_pct,p90_abs_pct,p99_abs_pct,max_abs_pct,min_signed_pct,max_signed_pct",
                List.of(accuracyLine("Sum of pings", naive), accuracyLine("Route every gap", routed), accuracyLine("This engine", engine)));
    }

    private static void accuracyByGap(String file, Batch batch) throws IOException {
        double[][] buckets = {{0, 15}, {15, 25}, {25, 35}, {35, 101}};
        List<String> lines = new ArrayList<>();
        for (double[] bucket : buckets) {
            List<Double> naive = new ArrayList<>();
            List<Double> engine = new ArrayList<>();
            for (Row r : batch.rows()) {
                double share = r.gapMeters() * 100.0 / r.trueMeters();
                if (share >= bucket[0] && share < bucket[1]) {
                    naive.add(percent(r.naiveMeters(), r.trueMeters()));
                    engine.add(percent(r.engineMeters(), r.trueMeters()));
                }
            }
            lines.add(String.format("%.0f-%.0f,%d,%.3f,%.3f", bucket[0], Math.min(bucket[1], 100), naive.size(),
                    naive.stream().mapToDouble(Double::doubleValue).average().orElse(0),
                    engine.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
        }
        write(file, "gap_share_pct,trips,naive_mean_signed_pct,engine_mean_signed_pct", lines);
    }

    private static void accuracyByDirection(Batch batch) throws IOException {
        List<String> lines = new ArrayList<>();
        for (TripEvidence.Direction direction : TripEvidence.Direction.values()) {
            List<Double> engine = new ArrayList<>();
            for (Row r : batch.rows()) {
                if (r.direction() == direction) {
                    engine.add(percent(r.engineMeters(), r.trueMeters()));
                }
            }
            lines.add(accuracyLine(direction.name(), engine) + "," + engine.size());
        }
        write("accuracy-by-direction.csv", "direction,mean_abs_pct,mean_signed_pct,p50_abs_pct,p90_abs_pct,p99_abs_pct,max_abs_pct,min_signed_pct,max_signed_pct,trips", lines);
    }

    private static void errorHistogram(Batch batch) throws IOException {
        double width = 0.25;
        int size = 16;
        int[] engine = new int[size];
        for (Row r : batch.rows()) {
            double signed = percent(r.engineMeters(), r.trueMeters());
            int bucket = (int) Math.floor((signed + 3.0) / width);
            engine[Math.max(0, Math.min(size - 1, bucket))]++;
        }
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            lines.add(String.format("%.2f,%.2f,%d", -3.0 + i * width, -3.0 + (i + 1) * width, engine[i]));
        }
        write("error-histogram.csv", "from_pct,to_pct,trips", lines);
    }

    private static void ruleMix(Batch batch) throws IOException {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<SegmentRule, long[]> entry : batch.ruleTotals().entrySet()) {
            lines.add(entry.getKey() + "," + entry.getValue()[0] + "," + entry.getValue()[1]);
        }
        write("rule-mix.csv", "rule,segments,meters", lines);
    }

    private static void latency(Batch batch) throws IOException {
        List<Double> millis = new ArrayList<>();
        for (Row r : batch.rows()) {
            millis.add(r.nanos() / 1e6);
        }
        double[] sorted = sorted(millis);
        List<String> lines = new ArrayList<>();
        for (double p : new double[]{50, 75, 90, 95, 99, 99.9, 100}) {
            lines.add(String.format("%s,%.3f", p, pick(sorted, p)));
        }
        write("engine-latency.csv", "percentile,millis", lines);
    }

    private static void providerCost(Batch batch) throws Exception {
        long routeEveryGap = 0;
        long oneCallPerEngineGap = 0;
        long engineCalls = 0;
        for (Row r : batch.rows()) {
            routeEveryGap += r.routeEveryGapCalls();
            oneCallPerEngineGap += r.gaps();
            engineCalls += r.providerCalls();
        }
        Batch noCache = runBatch(new Setup(FULL, CORES, 20, 0, true, -1, -1));
        long batchedOnly = noCache.rows().stream().mapToLong(Row::providerCalls).sum();
        int trips = batch.rows().size();
        write("provider-cost.csv", "approach,calls,calls_per_trip", List.of(
                String.format("Route every raw gap,%d,%.2f", routeEveryGap, (double) routeEveryGap / trips),
                String.format("One call per real gap,%d,%.2f", oneCallPerEngineGap, (double) oneCallPerEngineGap / trips),
                String.format("Batched,%d,%.2f", batchedOnly, (double) batchedOnly / trips),
                String.format("Batched and cached,%d,%.2f", engineCalls, (double) engineCalls / trips)));
    }

    private static void scaleByTrips() throws Exception {
        List<String> lines = new ArrayList<>();
        for (int trips : new int[]{1_000, 2_500, 5_000, 10_000, 20_000}) {
            Batch batch = runBatch(new Setup(trips, CORES, 20, 200_000, true, -1, -1));
            long pings = trips * 1505L;
            lines.add(String.format("%d,%.2f,%.0f,%d,%d", trips, batch.seconds(), trips / batch.seconds(), pings,
                    batch.peakHeapBytes() / (1024 * 1024)));
        }
        write("scale-by-trips.csv", "trips,seconds,trips_per_second,approx_pings,peak_heap_mb", lines);
    }

    private static void scaleByThreads() throws Exception {
        List<String> lines = new ArrayList<>();
        double oneThread = 0;
        for (int threads : new int[]{1, 2, 4, 8}) {
            Batch batch = runBatch(new Setup(4_000, threads, 0, 200_000, true, -1, -1));
            if (threads == 1) {
                oneThread = batch.seconds();
            }
            lines.add(String.format("%d,%.2f,%.2f", threads, batch.seconds(), oneThread / batch.seconds()));
        }
        write("scale-by-threads.csv", "threads,seconds,speedup", lines);
    }

    private static void providerLatency() throws Exception {
        List<String> lines = new ArrayList<>();
        for (long latency : new long[]{0, 5, 20, 50, 100, 200}) {
            Batch cached = runBatch(new Setup(5_000, CORES, latency, 200_000, true, -1, -1));
            Batch uncached = runBatch(new Setup(5_000, CORES, latency, 0, true, -1, -1));
            lines.add(String.format("%d,%.2f,%.2f", latency, cached.seconds(), uncached.seconds()));
        }
        write("provider-latency.csv", "provider_latency_ms,seconds_with_cache,seconds_without_cache", lines);
    }

    private static void cacheSize() throws Exception {
        List<String> lines = new ArrayList<>();
        for (int entries : new int[]{0, 100, 1_000, 5_000, 20_000, 200_000}) {
            Batch batch = runBatch(new Setup(FULL, CORES, 0, entries, true, -1, -1));
            long calls = batch.rows().stream().mapToLong(Row::providerCalls).sum();
            double hitRate = batch.hits() * 100.0 / Math.max(1, batch.hits() + batch.misses());
            lines.add(String.format("%d,%d,%.2f", entries, calls, hitRate));
        }
        write("cache-size.csv", "cache_entries,provider_calls,hit_rate_pct", lines);
    }

    // The provider is down for the middle of a run. Shows how many trips are affected,
    // how wrong the estimate was, and that recompute restores the exact healthy km.
    private static void outage(Batch healthy) throws Exception {
        int downAfter = 3_000;
        int upAfter = 6_000;
        FakeRoutingProvider provider = new FakeRoutingProvider(20, CORES * 2);
        Batch failed = runBatch(new Setup(FULL, CORES, 20, 200_000, true, downAfter, upAfter), provider);

        int bucketSize = 500;
        int buckets = FULL / bucketSize;
        int[] degraded = new int[buckets];
        for (Row r : failed.rows()) {
            if (r.status() == TripResult.Status.DEGRADED) {
                degraded[Math.min(buckets - 1, (r.finishOrder() - 1) / bucketSize)]++;
            }
        }
        List<String> timeline = new ArrayList<>();
        for (int i = 0; i < buckets; i++) {
            timeline.add((i + 1) * bucketSize + "," + degraded[i]);
        }
        write("outage-timeline.csv", "trips_finished,degraded_in_last_500", timeline);

        List<Double> estimateError = new ArrayList<>();
        int total = 0;
        int different = 0;
        for (Row r : failed.rows()) {
            if (r.status() == TripResult.Status.DEGRADED) {
                total++;
                long good = healthy.rows().get(r.index()).engineMeters();
                estimateError.add(percent(r.engineMeters(), good));
            }
        }
        DistanceCalculator recompute = new DistanceCalculator(CONFIG, new ResilientRouter(new FakeRoutingProvider(0, 1000),
                new RouteCache(200_000, 86_400_000L, System::currentTimeMillis),
                new CircuitBreaker(5, 1000, System::currentTimeMillis), 25, CORES));
        TripSimulator simulator = new TripSimulator(SEED, true);
        for (Row r : failed.rows()) {
            if (r.status() == TripResult.Status.DEGRADED) {
                long fixed = recompute.calculate(simulator.generate(r.index()).evidence()).totalMeters();
                if (fixed != healthy.rows().get(r.index()).engineMeters()) {
                    different++;
                }
            }
        }
        double[] sign = sorted(estimateError);
        write("outage-summary.csv", "degraded_trips,mean_estimate_error_pct,lowest_pct,highest_pct,different_after_recompute,audit_failures",
                List.of(String.format("%d,%.3f,%.3f,%.3f,%d,%d", total,
                        estimateError.stream().mapToDouble(Double::doubleValue).average().orElse(0),
                        sign[0], sign[sign.length - 1], different,
                        failed.rows().stream().filter(r -> r.auditProblems() > 0).count())));
    }

    // The same trips under different conditions must add up to exactly the same metres.
    private static void repeatability() throws Exception {
        List<String> lines = new ArrayList<>();
        String[] names = {"8 threads with cache", "8 threads without cache", "1 thread with cache", "8 threads, small cache"};
        Setup[] setups = {
                new Setup(FULL, CORES, 0, 200_000, true, -1, -1),
                new Setup(FULL, CORES, 0, 0, true, -1, -1),
                new Setup(FULL, 1, 0, 200_000, true, -1, -1),
                new Setup(FULL, CORES, 0, 500, true, -1, -1)};
        for (int i = 0; i < setups.length; i++) {
            Batch batch = runBatch(setups[i]);
            long total = batch.rows().stream().mapToLong(Row::engineMeters).sum();
            lines.add(names[i] + "," + total);
        }
        write("repeatability.csv", "condition,total_meters_over_all_trips", lines);
    }

    // Time per trip as pings grow, on normal data and on pure noise, the worst case for the chain filter.
    private static void complexity() throws IOException {
        PingCleaner cleaner = new PingCleaner(CONFIG);
        Random random = new Random(SEED);
        List<String> lines = new ArrayList<>();
        for (int n : new int[]{500, 1_000, 2_000, 4_000, 8_000, 16_000}) {
            List<Ping> normal = new ArrayList<>();
            List<Ping> noise = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                normal.add(new Ping(12.9 + i * 0.00005, 77.6 + i * 0.00005, i * 3000L, 10));
                noise.add(new Ping(8 + random.nextDouble() * 20, 70 + random.nextDouble() * 20, i * 3000L, 10));
            }
            lines.add(String.format("%d,%.3f,%.3f", n, medianMillis(cleaner, normal), medianMillis(cleaner, noise)));
        }
        write("complexity.csv", "pings,normal_ms,all_noise_ms", lines);
    }

    private static double medianMillis(PingCleaner cleaner, List<Ping> pings) {
        List<Double> times = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            long start = System.nanoTime();
            cleaner.clean(pings);
            times.add((System.nanoTime() - start) / 1e6);
        }
        return pick(sorted(times), 50);
    }

    private static void write(String file, String header, List<String> lines) throws IOException {
        List<String> all = new ArrayList<>();
        all.add(header);
        all.addAll(lines);
        Files.write(OUT.resolve(file), all);
        System.out.println("wrote " + file);
    }
}
