package com.moveinsync.distance.core;

import com.moveinsync.distance.model.RuleConfig;
import com.moveinsync.distance.model.Segment;
import com.moveinsync.distance.model.SegmentRule;
import com.moveinsync.distance.model.TrackPoint;

// Picks the rule and distance for one pair of points. The order here is the billing contract.
public class SegmentRuleEngine {

    private final RuleConfig config;

    public SegmentRuleEngine(RuleConfig config) {
        this.config = config;
    }

    // Rule 1 and rule 2 need no provider. Everything else is a real gap.
    public boolean needsRoute(TrackPoint from, TrackPoint to) {
        return !isShortGap(from, to) && !isParked(from, to);
    }

    public Segment localSegment(TrackPoint from, TrackPoint to) {
        SegmentRule rule = isShortGap(from, to) ? SegmentRule.SHORT_GAP_AERIAL : SegmentRule.PARKED;
        return new Segment(from, to, Math.round(aerialMeters(from, to)), rule);
    }

    // routeMeters is null when the provider gave no answer.
    public Segment routedSegment(TrackPoint from, TrackPoint to, Long routeMeters) {
        double aerial = aerialMeters(from, to);
        double estimate = aerial * config.roadFactor();
        long millis = millisBetween(from, to);

        if (routeMeters == null) {
            return new Segment(from, to, Math.round(estimate), SegmentRule.PROVIDER_DOWN_ESTIMATE);
        }
        if (routeMeters > config.maxRouteMeters()) {
            return new Segment(from, to, Math.round(estimate), SegmentRule.REJECTED_DISTANCE);
        }
        boolean routeTooFast = GeoMath.speedKmh(routeMeters, millis) > config.maxSpeedKmh();
        boolean estimateTooFast = GeoMath.speedKmh(estimate, millis) > config.maxSpeedKmh();
        if (routeTooFast && estimateTooFast) {
            return new Segment(from, to, Math.round(aerial), SegmentRule.REJECTED_SPEED);
        }
        return new Segment(from, to, routeMeters, SegmentRule.ROUTED);
    }

    private boolean isShortGap(TrackPoint from, TrackPoint to) {
        return millisBetween(from, to) < config.shortGapSeconds() * 1000 && speedKmh(from, to) <= config.maxSpeedKmh();
    }

    private boolean isParked(TrackPoint from, TrackPoint to) {
        return millisBetween(from, to) >= config.shortGapSeconds() * 1000 && speedKmh(from, to) < config.parkedSpeedKmh();
    }

    private double speedKmh(TrackPoint from, TrackPoint to) {
        return GeoMath.speedKmh(aerialMeters(from, to), millisBetween(from, to));
    }

    private long millisBetween(TrackPoint from, TrackPoint to) {
        return to.timeMillis() - from.timeMillis();
    }

    private double aerialMeters(TrackPoint from, TrackPoint to) {
        return GeoMath.metersBetween(from.latitude(), from.longitude(), to.latitude(), to.longitude());
    }
}
