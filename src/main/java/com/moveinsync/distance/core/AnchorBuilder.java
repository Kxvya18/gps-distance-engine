package com.moveinsync.distance.core;

import com.moveinsync.distance.model.Milestone;
import com.moveinsync.distance.model.Ping;
import com.moveinsync.distance.model.RuleConfig;
import com.moveinsync.distance.model.TrackPoint;
import com.moveinsync.distance.model.TripEvidence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// Adds the known points to the clean pings: employee, no-show and office anchors.
public class AnchorBuilder {

    private final RuleConfig config;

    public AnchorBuilder(RuleConfig config) {
        this.config = config;
    }

    public List<TrackPoint> build(TripEvidence evidence, List<Ping> cleanPings) {
        List<TrackPoint> points = new ArrayList<>();
        for (Ping ping : cleanPings) {
            points.add(new TrackPoint(ping.latitude(), ping.longitude(), ping.timestampMillis(), TrackPoint.Source.PING));
        }

        for (Milestone milestone : evidence.milestones()) {
            switch (milestone.type()) {
                case PICKUP, DROP -> addAnchor(points, milestone, TrackPoint.Source.EMPLOYEE_ANCHOR);
                case NO_SHOW -> {
                    if (config.includeNoShows()) {
                        addAnchor(points, milestone, TrackPoint.Source.NO_SHOW_ANCHOR);
                    }
                }
                case TRIP_START -> {
                    if (evidence.direction() == TripEvidence.Direction.LOGOUT) {
                        addOfficeAnchor(points, milestone, evidence);
                    }
                }
                case TRIP_END -> {
                    if (evidence.direction() == TripEvidence.Direction.LOGIN) {
                        addOfficeAnchor(points, milestone, evidence);
                    }
                }
            }
        }

        points.sort(Comparator.comparingLong(TrackPoint::timeMillis)
                .thenComparing(TrackPoint::source)
                .thenComparingDouble(TrackPoint::latitude)
                .thenComparingDouble(TrackPoint::longitude));
        return points;
    }

    // Uses the actual point if valid, otherwise the fallback. A milestone with neither adds nothing.
    private void addAnchor(List<TrackPoint> points, Milestone m, TrackPoint.Source source) {
        if (isValid(m.latitude(), m.longitude(), m.timeMillis())) {
            points.add(new TrackPoint(m.latitude(), m.longitude(), m.timeMillis(), source));
        } else if (isValid(m.fallbackLatitude(), m.fallbackLongitude(), m.fallbackTimeMillis())) {
            points.add(new TrackPoint(m.fallbackLatitude(), m.fallbackLongitude(), m.fallbackTimeMillis(), source));
        }
    }

    // The office end of the trip. The actual point is used only if it is close to the office.
    private void addOfficeAnchor(List<TrackPoint> points, Milestone end, TripEvidence evidence) {
        if (end.timeMillis() == null) {
            return;
        }
        double lat = evidence.officeLatitude();
        double lon = evidence.officeLongitude();
        if (isValid(end.latitude(), end.longitude(), end.timeMillis())
                && GeoMath.metersBetween(end.latitude(), end.longitude(), lat, lon) <= config.officeRadiusMeters()) {
            lat = end.latitude();
            lon = end.longitude();
        }
        points.add(new TrackPoint(lat, lon, end.timeMillis(), TrackPoint.Source.OFFICE_ANCHOR));
    }

    private boolean isValid(Double lat, Double lon, Long time) {
        if (lat == null || lon == null || time == null) {
            return false;
        }
        boolean inRange = lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
        boolean isNullIsland = lat == 0 && lon == 0;
        return inRange && !isNullIsland;
    }
}
