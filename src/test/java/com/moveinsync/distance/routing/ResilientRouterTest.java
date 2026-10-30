package com.moveinsync.distance.routing;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Tests caching, batching and fallback in ResilientRouter.
class ResilientRouterTest {

    private final FakeRoutingProvider provider = new FakeRoutingProvider(0, 100);
    private final RouteCache cache = new RouteCache(100, 60_000, System::currentTimeMillis);
    private final CircuitBreaker breaker = new CircuitBreaker(3, 60_000, System::currentTimeMillis);
    private final ResilientRouter router = new ResilientRouter(provider, cache, breaker, 10, 8);

    private final RouteRequest request = new RouteRequest(12.9, 77.6, 13.0, 77.7);

    @Test
    void secondAskComesFromTheCache() {
        router.routeAll(List.of(request));
        RouteBatch second = router.routeAll(List.of(request));
        assertEquals(0, second.providerCalls());
        assertEquals(1, provider.callCount());
    }

    @Test
    void pointsInTheSameCellShareAnAnswer() {
        Long first = router.routeAll(List.of(request)).meters().get(0);
        RouteRequest nearby = new RouteRequest(12.90001, 77.60001, 13.00001, 77.70001);
        assertEquals(first, router.routeAll(List.of(nearby)).meters().get(0));
        assertEquals(1, provider.callCount());
    }

    @Test
    void largeRequestListIsSplitIntoBatches() {
        List<RouteRequest> requests = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            requests.add(new RouteRequest(12.9, 77.6, 13.0 + i * 0.01, 77.7));
        }
        RouteBatch batch = router.routeAll(requests);
        assertEquals(3, batch.providerCalls());
        assertEquals(25, batch.meters().size());
    }

    @Test
    void duplicateRequestsInOneListAreAskedOnce() {
        RouteBatch batch = router.routeAll(List.of(request, request, request));
        assertEquals(1, batch.providerCalls());
        assertEquals(batch.meters().get(0), batch.meters().get(2));
    }

    @Test
    void failedCallIsRetriedOnceThenGivesNoAnswer() {
        provider.setDown(true);
        RouteBatch batch = router.routeAll(List.of(request));
        assertNull(batch.meters().get(0));
        assertEquals(2, batch.providerCalls());
        assertEquals(1, router.retryCount());
    }

    @Test
    void hangingProviderTimesOutAndGivesNoAnswer() {
        ResilientRouter quick = new ResilientRouter(provider, cache, breaker, 10, 8, 50);
        provider.setExtraDelayMillis(10_000);

        long start = System.currentTimeMillis();
        RouteBatch batch = quick.routeAll(List.of(request));
        long took = System.currentTimeMillis() - start;

        assertNull(batch.meters().get(0));
        assertEquals(2, quick.timeoutCount());
        assertEquals(2, quick.failureCount());
        assertTrue(took < 2000, "took " + took + " ms");
    }

    @Test
    void repeatedTimeoutsOpenTheBreaker() {
        ResilientRouter quick = new ResilientRouter(provider, cache, breaker, 10, 8, 20);
        provider.setExtraDelayMillis(10_000);
        for (int i = 0; i < 3; i++) {
            quick.routeAll(List.of(new RouteRequest(12.9, 77.6, 13.0 + i * 0.01, 77.7)));
        }
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
    }

    @Test
    void timeoutFreesTheCallSlot() {
        ResilientRouter oneSlot = new ResilientRouter(provider, cache, new CircuitBreaker(100, 60_000, System::currentTimeMillis), 10, 1, 20);
        provider.setExtraDelayMillis(10_000);
        oneSlot.routeAll(List.of(request));

        provider.setExtraDelayMillis(0);
        assertEquals(1, oneSlot.routeAll(List.of(request)).meters().stream().filter(m -> m != null).count());
    }

    @Test
    void whenOneBatchFailsTheOtherBatchesStillAnswer() {
        AtomicInteger callNumber = new AtomicInteger();
        RoutingProvider failsOnSecondBatch = batch -> {
            int call = callNumber.incrementAndGet();
            if (call == 2 || call == 3) {
                throw new RoutingException("second batch fails, including its retry");
            }
            return provider.drivingMeters(batch);
        };
        ResilientRouter partial = new ResilientRouter(failsOnSecondBatch, cache, breaker, 10, 8);

        List<RouteRequest> requests = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            requests.add(new RouteRequest(12.9, 77.6, 13.0 + i * 0.01, 77.7));
        }
        List<Long> answers = partial.routeAll(requests).meters();

        assertEquals(10, answers.subList(0, 10).stream().filter(m -> m != null).count());
        assertEquals(0, answers.subList(10, 20).stream().filter(m -> m != null).count());
        assertEquals(5, answers.subList(20, 25).stream().filter(m -> m != null).count());
    }

    @Test
    void rateLimitedCallsAreTreatedAsFailuresNotCrashes() throws Exception {
        FakeRoutingProvider limited = new FakeRoutingProvider(300, 1);
        ResilientRouter busy = new ResilientRouter(limited, cache, breaker, 10, 2);
        RouteRequest other = new RouteRequest(12.9, 77.6, 14.0, 77.7);

        Thread first = new Thread(() -> busy.routeAll(List.of(request)));
        first.start();
        Thread.sleep(50);
        RouteBatch refused = busy.routeAll(List.of(other));
        first.join();

        assertNull(refused.meters().get(0));
        assertTrue(busy.failureCount() >= 1);
    }

    @Test
    void openBreakerMeansNoProviderCalls() {
        provider.setDown(true);
        for (int i = 0; i < 3; i++) {
            router.routeAll(List.of(new RouteRequest(12.9, 77.6, 13.0 + i * 0.01, 77.7)));
        }
        long callsBefore = provider.callCount();
        router.routeAll(List.of(new RouteRequest(12.9, 77.6, 14.0, 77.7)));
        assertEquals(callsBefore, provider.callCount());
    }
}
