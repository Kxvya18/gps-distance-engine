package com.moveinsync.distance.audit;

import com.moveinsync.distance.model.RuleConfig;
import com.moveinsync.distance.model.Segment;
import com.moveinsync.distance.model.TrackPoint;
import com.moveinsync.distance.model.TripResult;

import java.util.ArrayList;
import java.util.List;

// Separately rechecks a finished trip before it can be invoiced.
// It imports nothing from the core package and has its own distance formula,
// so a bug in the engine does not automatically hide itself here.
public class TripAuditor {

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;
    private static final long ROUNDING_TOLERANCE_METERS = 1;
    // A route is measured between cell centres, so it can fall a little under the straight line.
    private static final double SNAP_TOLERANCE_METERS = 150;

    private final RuleConfig config;

    public TripAuditor(RuleConfig config) {
        this.config = config;
    }

    // Returns a list of problems. An empty list means the trip passed.
    public List<String> audit(TripResult result) {
        List<String> problems = new ArrayList<>();
        long sum = 0;
        Segment previous = null;

        for (Segment segment : result.segments()) {
            sum += segment.distanceMeters();
            if (previous != null && !previous.to().equals(segment.from())) {
                problems.add("segments are not connected at " + segment.from().timeMillis());
            }
            if (segment.to().timeMillis() < segment.from().timeMillis()) {
                problems.add("time runs backwards at " + segment.from().timeMillis());
            }
            checkRule(segment, result.status(), problems);
            previous = segment;
        }

        if (sum != result.totalMeters()) {
            problems.add("total " + result.totalMeters() + " is not the sum of segments " + sum);
        }
        return problems;
    }

    private void checkRule(Segment s, TripResult.Status status, List<String> problems) {
        double aerial = aerialMeters(s.from(), s.to());
        double seconds = (s.to().timeMillis() - s.from().timeMillis()) / 1000.0;
        double speed = kmh(aerial, seconds);
        boolean plausible = aerial <= config.maxAccuracyMeters() || speed <= config.maxSpeedKmh();
        boolean shortGap = seconds < config.shortGapSeconds() && plausible;
        boolean parked = seconds >= config.shortGapSeconds() && speed < config.parkedSpeedKmh();
        double estimate = aerial * config.roadFactor();

        switch (s.rule()) {
            case SHORT_GAP_AERIAL -> {
                require(shortGap, s, "is not a short gap", problems);
                requireClose(s, aerial, problems);
            }
            case PARKED -> {
                require(parked, s, "is not a parked gap", problems);
                requireClose(s, aerial, problems);
            }
            case ROUTED -> {
                require(!shortGap && !parked, s, "should not have been routed", problems);
                require(s.distanceMeters() <= config.maxRouteMeters(), s, "route is over the limit", problems);
                require(s.distanceMeters() >= aerial - SNAP_TOLERANCE_METERS, s, "route is shorter than the straight line", problems);
            }
            case PROVIDER_DOWN_ESTIMATE -> {
                requireClose(s, estimate, problems);
                require(status == TripResult.Status.DEGRADED, s, "estimate on a trip not marked degraded", problems);
            }
            case REJECTED_DISTANCE -> {
                requireClose(s, estimate, problems);
                require(status != TripResult.Status.OK, s, "rejected route on an OK trip", problems);
            }
            case REJECTED_SPEED -> {
                requireClose(s, aerial, problems);
                require(kmh(estimate, seconds) > config.maxSpeedKmh(), s, "estimate was not too fast", problems);
                require(status != TripResult.Status.OK, s, "rejected route on an OK trip", problems);
            }
        }
    }

    private void require(boolean condition, Segment s, String message, List<String> problems) {
        if (!condition) {
            problems.add(s.rule() + " at " + s.from().timeMillis() + " " + message);
        }
    }

    private void requireClose(Segment s, double expected, List<String> problems) {
        if (Math.abs(s.distanceMeters() - expected) > ROUNDING_TOLERANCE_METERS) {
            problems.add(s.rule() + " at " + s.from().timeMillis() + " has " + s.distanceMeters() + " m, expected about " + Math.round(expected));
        }
    }

    private double kmh(double meters, double seconds) {
        if (seconds <= 0) {
            return meters == 0 ? 0 : Double.POSITIVE_INFINITY;
        }
        return meters / seconds * 3.6;
    }

    // Uses the atan2 form of the formula, which is different from the engine's asin form.
    private double aerialMeters(TrackPoint a, TrackPoint b) {
        double lat1 = StrictMath.toRadians(a.latitude());
        double lat2 = StrictMath.toRadians(b.latitude());
        double dLat = lat2 - lat1;
        double dLon = StrictMath.toRadians(b.longitude() - a.longitude());
        double h = StrictMath.pow(StrictMath.sin(dLat / 2), 2)
                + StrictMath.cos(lat1) * StrictMath.cos(lat2) * StrictMath.pow(StrictMath.sin(dLon / 2), 2);
        return EARTH_RADIUS_METERS * 2 * StrictMath.atan2(StrictMath.sqrt(h), StrictMath.sqrt(1 - h));
    }
}
