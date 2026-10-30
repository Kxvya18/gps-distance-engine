package com.moveinsync.distance.routing;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Concurrency test: many threads use the cache and breaker.
class ConcurrencyTest {

    @Test
    void manyThreadsGetTheSameAnswersAndKeepTheCacheBounded() throws Exception {
        FakeRoutingProvider provider = new FakeRoutingProvider(1, 100);
        RouteCache cache = new RouteCache(50, 60_000, System::currentTimeMillis);
        CircuitBreaker breaker = new CircuitBreaker(5, 1000, System::currentTimeMillis);
        ResilientRouter router = new ResilientRouter(provider, cache, breaker, 25, 16);

        List<RouteRequest> requests = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            requests.add(new RouteRequest(12.9, 77.6, 12.9 + i * 0.001, 77.6 + i * 0.002));
        }
        List<Long> expected = router.routeAll(requests).meters();

        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<List<Long>>> runs = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            runs.add(pool.submit(() -> router.routeAll(requests).meters()));
        }
        for (Future<List<Long>> run : runs) {
            assertEquals(expected, run.get());
        }
        pool.shutdown();

        assertTrue(cache.size() <= 50);
    }

    @Test
    void breakerOpensOnceUnderConcurrentFailures() throws Exception {
        FakeRoutingProvider provider = new FakeRoutingProvider(0, 100);
        provider.setDown(true);
        RouteCache cache = new RouteCache(50, 60_000, System::currentTimeMillis);
        CircuitBreaker breaker = new CircuitBreaker(5, 60_000, System::currentTimeMillis);
        ResilientRouter router = new ResilientRouter(provider, cache, breaker, 25, 16);

        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<RouteBatch>> runs = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            RouteRequest request = new RouteRequest(12.9, 77.6, 13.0 + i * 0.01, 77.7);
            runs.add(pool.submit(() -> router.routeAll(List.of(request))));
        }
        for (Future<RouteBatch> run : runs) {
            assertEquals(null, run.get().meters().get(0));
        }
        pool.shutdown();

        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertTrue(provider.callCount() < 100, "breaker should stop most calls, saw " + provider.callCount());
    }
}
