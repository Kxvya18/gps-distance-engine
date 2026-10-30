package com.moveinsync.distance.model;

// All limits in one place. A result stores ruleVersion, so old bills can be repeated exactly.
public record RuleConfig(
        int ruleVersion,
        double maxSpeedKmh,
        double maxAccuracyMeters,
        long shortGapSeconds,
        double parkedSpeedKmh,
        long maxRouteMeters,
        double roadFactor,
        double officeRadiusMeters,
        boolean includeNoShows) {

    public static RuleConfig defaults() {
        return new RuleConfig(1, 120.0, 50.0, 70, 5.0, 100_000, 1.4, 400.0, false);
    }
}