package com.moveinsync.distance.routing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

// Puts the cache, batching, retry and circuit breaker in front of the provider.
// The provider is always asked about snapped cell centres, so one answer belongs to
// one cell pair. That keeps the km identical whether an answer came from the cache or not.
public class ResilientRouter implements Router {

    private static final double CELL_DEGREES = 0.0005;
    private static final int MAX_ATTEMPTS = 2;
    private static final long RETRY_PAUSE_MILLIS = 20;
    private static final long DEFAULT_TIMEOUT_MILLIS = 2000;

    private final RoutingProvider provider;
    private final RouteCache cache;
    private final CircuitBreaker breaker;
    private final int batchSize;
    private final Semaphore concurrentCalls;
    private final ExecutorService callers;
    private final long timeoutMillis;
    private final AtomicLong timeouts = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();

    public ResilientRouter(RoutingProvider provider, RouteCache cache, CircuitBreaker breaker,
                           int batchSize, int maxConcurrentCalls) {
        this(provider, cache, breaker, batchSize, maxConcurrentCalls, DEFAULT_TIMEOUT_MILLIS);
    }

    public ResilientRouter(RoutingProvider provider, RouteCache cache, CircuitBreaker breaker,
                           int batchSize, int maxConcurrentCalls, long timeoutMillis) {
        this.provider = provider;
        this.cache = cache;
        this.breaker = breaker;
        this.batchSize = batchSize;
        this.concurrentCalls = new Semaphore(maxConcurrentCalls);
        this.timeoutMillis = timeoutMillis;
        this.callers = Executors.newFixedThreadPool(maxConcurrentCalls, runnable -> {
            Thread thread = new Thread(runnable, "routing-call");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public RouteBatch routeAll(List<RouteRequest> requests) {
        Map<String, Long> answers = new LinkedHashMap<>();
        Map<String, RouteRequest> missing = new LinkedHashMap<>();

        List<String> keys = new ArrayList<>();
        for (RouteRequest request : requests) {
            String key = keyOf(request);
            keys.add(key);
            if (answers.containsKey(key) || missing.containsKey(key)) {
                continue;
            }
            Long cached = cache.get(key);
            if (cached != null) {
                answers.put(key, cached);
            } else {
                missing.put(key, snapped(request));
            }
        }

        int calls = askProvider(missing, answers);

        List<Long> ordered = new ArrayList<>();
        for (String key : keys) {
            ordered.add(answers.get(key));
        }
        return new RouteBatch(ordered, calls);
    }

    private int askProvider(Map<String, RouteRequest> missing, Map<String, Long> answers) {
        List<String> keys = new ArrayList<>(missing.keySet());
        int calls = 0;
        for (int start = 0; start < keys.size(); start += batchSize) {
            List<String> chunkKeys = keys.subList(start, Math.min(start + batchSize, keys.size()));
            List<RouteRequest> chunk = new ArrayList<>();
            for (String key : chunkKeys) {
                chunk.add(missing.get(key));
            }
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                if (!breaker.allowRequest()) {
                    break;
                }
                calls++;
                if (callOnce(chunkKeys, chunk, answers)) {
                    break;
                }
                if (attempt < MAX_ATTEMPTS) {
                    retries.incrementAndGet();
                    pauseBeforeRetry();
                }
            }
        }
        return calls;
    }

    // The call runs on its own thread so a provider that hangs cannot hang the trip.
    private boolean callOnce(List<String> keys, List<RouteRequest> chunk, Map<String, Long> answers) {
        concurrentCalls.acquireUninterruptibly();
        Future<List<Long>> call = callers.submit(() -> provider.drivingMeters(chunk));
        try {
            List<Long> meters = call.get(timeoutMillis, TimeUnit.MILLISECONDS);
            for (int i = 0; i < keys.size(); i++) {
                cache.put(keys.get(i), meters.get(i));
                answers.put(keys.get(i), meters.get(i));
            }
            breaker.recordSuccess();
            return true;
        } catch (TimeoutException e) {
            call.cancel(true);
            timeouts.incrementAndGet();
            return callFailed();
        } catch (ExecutionException e) {
            return callFailed();
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            return callFailed();
        } finally {
            concurrentCalls.release();
        }
    }

    private boolean callFailed() {
        failures.incrementAndGet();
        breaker.recordFailure();
        return false;
    }

    private void pauseBeforeRetry() {
        try {
            Thread.sleep(RETRY_PAUSE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long cellOf(double degrees) {
        return Math.round(degrees / CELL_DEGREES);
    }

    private String keyOf(RouteRequest r) {
        return cellOf(r.fromLatitude()) + ":" + cellOf(r.fromLongitude()) + ":"
                + cellOf(r.toLatitude()) + ":" + cellOf(r.toLongitude());
    }

    private RouteRequest snapped(RouteRequest r) {
        return new RouteRequest(cellOf(r.fromLatitude()) * CELL_DEGREES, cellOf(r.fromLongitude()) * CELL_DEGREES,
                cellOf(r.toLatitude()) * CELL_DEGREES, cellOf(r.toLongitude()) * CELL_DEGREES);
    }

    public long timeoutCount() {
        return timeouts.get();
    }

    public long failureCount() {
        return failures.get();
    }

    public long retryCount() {
        return retries.get();
    }

    public CircuitBreaker breaker() {
        return breaker;
    }

    public RouteCache cache() {
        return cache;
    }
}
