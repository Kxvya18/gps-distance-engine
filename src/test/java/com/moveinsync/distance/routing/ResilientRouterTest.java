package com.moveinsync.distance.routing;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
