package com.moveinsync.distance.monitoring;

import com.moveinsync.distance.repository.TripRecordRepository;
import com.moveinsync.distance.routing.CircuitBreaker;
import com.moveinsync.distance.routing.ResilientRouter;
import com.moveinsync.distance.routing.RouteCache;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

// Counters an on-call engineer needs: what is failing, what is queued, what is being rejected.
@Component
public class TripMetrics {

    private final MeterRegistry registry;

    public TripMetrics(MeterRegistry registry, ResilientRouter router, TripRecordRepository trips) {
        this.registry = registry;
        RouteCache cache = router.cache();
        Gauge.builder("route_cache_hits", cache, RouteCache::hitCount).register(registry);
        Gauge.builder("route_cache_misses", cache, RouteCache::missCount).register(registry);
        Gauge.builder("route_cache_size", cache, RouteCache::size).register(registry);
        Gauge.builder("provider_failures", router, ResilientRouter::failureCount).register(registry);
        Gauge.builder("provider_retries", router, ResilientRouter::retryCount).register(registry);
        Gauge.builder("provider_breaker_open", router,
                r -> r.breaker().state() == CircuitBreaker.State.OPEN ? 1 : 0).register(registry);
        Gauge.builder("trips_degraded_waiting", trips, t -> t.countByStatus("DEGRADED")).register(registry);
    }

    public void tripProcessed(String status) {
        registry.counter("trips_processed", "status", status).increment();
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
