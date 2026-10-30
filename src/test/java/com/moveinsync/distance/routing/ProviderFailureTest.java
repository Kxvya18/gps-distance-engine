package com.moveinsync.distance.routing;

import com.moveinsync.distance.audit.TripAuditor;
import com.moveinsync.distance.core.DistanceCalculator;
import com.moveinsync.distance.model.RuleConfig;
import com.moveinsync.distance.model.TripEvidence;
import com.moveinsync.distance.model.TripResult;
import com.moveinsync.distance.simulation.TripSimulator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Failure test: the provider dies halfway through a run and then recovers.
class ProviderFailureTest {

    private static final int TRIPS = 300;

    private final RuleConfig config = RuleConfig.defaults();
    private final TripSimulator simulator = new TripSimulator(5);

    @Test
    void tripsAreDegradedDuringTheOutageAndRecoverToTheExactHealthyKm() {
        AtomicLong now = new AtomicLong(0);
        FakeRoutingProvider provider = new FakeRoutingProvider(0, 100);
        RouteCache cache = new RouteCache(100_000, Long.MAX_VALUE, now::get);
        CircuitBreaker breaker = new CircuitBreaker(5, 1000, now::get);
        DistanceCalculator calculator = new DistanceCalculator(config, new ResilientRouter(provider, cache, breaker, 25, 8));

        List<TripEvidence> trips = new ArrayList<>();
        for (int i = 0; i < TRIPS; i++) {
            trips.add(simulator.generate(i).evidence());
        }

        List<TripResult> results = new ArrayList<>();
        for (int i = 0; i < TRIPS; i++) {
            if (i == TRIPS / 2) {
                provider.setDown(true);
            }
            results.add(calculator.calculate(trips.get(i)));
        }

        long degraded = results.stream().filter(r -> r.status() == TripResult.Status.DEGRADED).count();
        assertTrue(degraded > 0, "expected degraded trips");
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        for (int i = 0; i < TRIPS / 2; i++) {
            assertTrue(results.get(i).status() != TripResult.Status.DEGRADED, "trip before the outage");
        }

        TripAuditor auditor = new TripAuditor(config);
        results.forEach(r -> assertTrue(auditor.audit(r).isEmpty(), "degraded results must still pass the audit"));

        provider.setDown(false);
        now.addAndGet(1000);

        DistanceCalculator healthyCalculator = new DistanceCalculator(config, new ResilientRouter(
                new FakeRoutingProvider(0, 100), new RouteCache(100_000, Long.MAX_VALUE, now::get),
                new CircuitBreaker(5, 1000, now::get), 25, 8));

        for (int i = 0; i < TRIPS; i++) {
            if (results.get(i).status() == TripResult.Status.DEGRADED) {
                TripResult fixed = calculator.calculate(trips.get(i));
                TripResult healthy = healthyCalculator.calculate(trips.get(i));
                assertEquals(TripResult.Status.OK, fixed.status());
                assertEquals(healthy.totalMeters(), fixed.totalMeters(), "trip " + i);
            }
        }
    }
}
