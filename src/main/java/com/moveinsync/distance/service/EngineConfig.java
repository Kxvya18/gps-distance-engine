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

    @Bean
    public RuleConfig ruleConfig() {
        return RuleConfig.defaults();
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
