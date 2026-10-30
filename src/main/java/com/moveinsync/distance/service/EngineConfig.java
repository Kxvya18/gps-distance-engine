package com.moveinsync.distance.service;

import com.moveinsync.distance.audit.TripAuditor;
import com.moveinsync.distance.core.DistanceCalculator;
import com.moveinsync.distance.model.RuleConfig;
import com.moveinsync.distance.routing.CircuitBreaker;
import com.moveinsync.distance.routing.FakeRoutingProvider;
import com.moveinsync.distance.routing.ResilientRouter;
import com.moveinsync.distance.routing.RouteCache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Wires the plain engine classes into Spring. The engine itself knows nothing about Spring.
@Configuration
@EnableScheduling
public class EngineConfig {

    private static final int CACHE_ENTRIES = 200_000;
    private static final long CACHE_TTL_MILLIS = 24 * 60 * 60 * 1000L;
    private static final int BREAKER_FAILURES = 5;
    private static final long BREAKER_COOL_DOWN_MILLIS = 5000;
    private static final int BATCH_SIZE = 25;
    private static final int MAX_CONCURRENT_CALLS = 16;

    // Changing any of these limits changes the km, so it must also raise the rule version.
    @Bean
    public RuleConfig ruleConfig(@Value("${app.rules.version:1}") int version,
                                 @Value("${app.rules.max-speed-kmh:120}") double maxSpeedKmh,
                                 @Value("${app.rules.max-accuracy-meters:50}") double maxAccuracyMeters,
                                 @Value("${app.rules.short-gap-seconds:70}") long shortGapSeconds,
                                 @Value("${app.rules.parked-speed-kmh:5}") double parkedSpeedKmh,
                                 @Value("${app.rules.max-route-meters:100000}") long maxRouteMeters,
                                 @Value("${app.rules.road-factor:1.4}") double roadFactor,
                                 @Value("${app.rules.office-radius-meters:400}") double officeRadiusMeters,
                                 @Value("${app.rules.include-no-shows:false}") boolean includeNoShows) {
        return new RuleConfig(version, maxSpeedKmh, maxAccuracyMeters, shortGapSeconds, parkedSpeedKmh,
                maxRouteMeters, roadFactor, officeRadiusMeters, includeNoShows);
    }

    @Bean
    public FakeRoutingProvider routingProvider(@Value("${app.provider-latency-millis}") long latencyMillis) {
        return new FakeRoutingProvider(latencyMillis, MAX_CONCURRENT_CALLS * 2);
    }

    @Bean
    public ResilientRouter router(FakeRoutingProvider provider) {
        RouteCache cache = new RouteCache(CACHE_ENTRIES, CACHE_TTL_MILLIS, System::currentTimeMillis);
        CircuitBreaker breaker = new CircuitBreaker(BREAKER_FAILURES, BREAKER_COOL_DOWN_MILLIS, System::currentTimeMillis);
        return new ResilientRouter(provider, cache, breaker, BATCH_SIZE, MAX_CONCURRENT_CALLS);
    }

    @Bean
    public DistanceCalculator distanceCalculator(RuleConfig config, ResilientRouter router) {
        return new DistanceCalculator(config, router);
    }

    @Bean
    public TripAuditor tripAuditor(RuleConfig config) {
        return new TripAuditor(config);
    }
}
