package com.moveinsync.distance.monitoring;

import com.moveinsync.distance.repository.TripRecordRepository;
import com.moveinsync.distance.routing.CircuitBreaker;
import com.moveinsync.distance.routing.ResilientRouter;
import com.moveinsync.distance.routing.RouteCache;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

// Counters an on-call engineer needs: lag, error rate, rejections, retries and queue depth.
// Error rate also comes from http_server_requests, which Spring records automatically.
@Component
public class TripMetrics {

    private final MeterRegistry registry;
    private final TripRecordRepository trips;

    public TripMetrics(MeterRegistry registry, ResilientRouter router, TripRecordRepository trips) {
        this.registry = registry;
        this.trips = trips;
        RouteCache cache = router.cache();
        Gauge.builder("route_cache_hits", cache, RouteCache::hitCount).register(registry);
        Gauge.builder("route_cache_misses", cache, RouteCache::missCount).register(registry);
        Gauge.builder("route_cache_size", cache, RouteCache::size).register(registry);
        Gauge.builder("provider_failures", router, ResilientRouter::failureCount).register(registry);
        Gauge.builder("provider_timeouts", router, ResilientRouter::timeoutCount).register(registry);
        Gauge.builder("provider_retries", router, ResilientRouter::retryCount).register(registry);
        Gauge.builder("provider_breaker_open", router,
                r -> r.breaker().state() == CircuitBreaker.State.OPEN ? 1 : 0).register(registry);
        Gauge.builder("trips_degraded_waiting", trips, t -> t.countByStatus("DEGRADED")).register(registry);
        Gauge.builder("trips_degraded_oldest_age_seconds", this, TripMetrics::oldestDegradedAgeSeconds).register(registry);
    }

    // Recompute lag: how long the oldest degraded trip has been waiting.
    private double oldestDegradedAgeSeconds() {
        return trips.findFirstByStatusOrderByDegradedSinceAsc("DEGRADED")
                .map(trip -> (double) Duration.between(trip.getDegradedSince(), Instant.now()).toSeconds())
                .orElse(0.0);
    }

    public void tripProcessed(String status) {
        registry.counter("trips_processed", "status", status).increment();
    }

    public void segmentRejected(String rule) {
        registry.counter("segments_rejected", "rule", rule).increment();
    }

    public void auditFailed() {
        registry.counter("trips_failed_audit").increment();
    }

    public void correctionIssued() {
        registry.counter("invoice_corrections").increment();
    }

    public void recomputed() {
        registry.counter("trips_recomputed").increment();
    }

    public void concurrentUpdateRetried() {
        registry.counter("trip_update_retries").increment();
    }
}
