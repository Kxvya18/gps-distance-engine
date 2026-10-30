package com.moveinsync.distance.service;

import com.moveinsync.distance.model.RuleConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Tests that the limits and the no-show flag come from configuration.
@SpringBootTest(properties = {"app.rules.max-speed-kmh=90", "app.rules.include-no-shows=true"})
class RuleConfigurationTest {

    @Autowired
    private RuleConfig config;

    @Test
    void configurationOverridesTheDefaults() {
        assertEquals(90.0, config.maxSpeedKmh());
        assertTrue(config.includeNoShows());
    }

    @Test
    void otherLimitsKeepTheSpecDefaults() {
        RuleConfig spec = RuleConfig.defaults();
        assertEquals(spec.shortGapSeconds(), config.shortGapSeconds());
        assertEquals(spec.officeRadiusMeters(), config.officeRadiusMeters());
        assertEquals(spec.maxRouteMeters(), config.maxRouteMeters());
        assertEquals(spec.roadFactor(), config.roadFactor());
    }
}
