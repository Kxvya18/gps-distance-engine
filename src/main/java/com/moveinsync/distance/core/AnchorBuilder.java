package com.moveinsync.distance.core;

import com.moveinsync.distance.model.AdHocRider;
import com.moveinsync.distance.model.Milestone;
import com.moveinsync.distance.model.Ping;
import com.moveinsync.distance.model.PlannedStop;
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
                case PICKUP, DROP -> addEmployeeAnchor(points, milestone, evidence);
                case NO_SHOW -> {
                    if (config.includeNoShows()) {
                        addNoShowAnchor(points, milestone, evidence);
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

    // Actual point if valid. Otherwise an ad-hoc rider's profile home and sign-in time,
    // or a planned rider's planned location and time.
    private void addEmployeeAnchor(List<TrackPoint> points, Milestone m, TripEvidence evidence) {
        TrackPoint.Source source = TrackPoint.Source.EMPLOYEE_ANCHOR;
        if (isValid(m.latitude(), m.longitude(), m.timeMillis())) {
            points.add(new TrackPoint(m.latitude(), m.longitude(), m.timeMillis(), source));
            return;
        }
        for (AdHocRider rider : evidence.adHocRiders()) {
            if (rider.employeeId().equals(m.employeeId())) {
                points.add(new TrackPoint(rider.homeLatitude(), rider.homeLongitude(), rider.signInTimeMillis(), source));
                return;
            }
        }
        addPlannedStop(points, m, evidence, source);
    }

    // Actual no-show location if valid, otherwise the planned location.
    private void addNoShowAnchor(List<TrackPoint> points, Milestone m, TripEvidence evidence) {
        TrackPoint.Source source = TrackPoint.Source.NO_SHOW_ANCHOR;
        if (isValid(m.latitude(), m.longitude(), m.timeMillis())) {
            points.add(new TrackPoint(m.latitude(), m.longitude(), m.timeMillis(), source));
            return;
        }
        addPlannedStop(points, m, evidence, source);
    }

    private void addPlannedStop(List<TrackPoint> points, Milestone m, TripEvidence evidence, TrackPoint.Source source) {
        for (PlannedStop stop : evidence.plannedStops()) {
            if (stop.employeeId().equals(m.employeeId())) {
                points.add(new TrackPoint(stop.latitude(), stop.longitude(), stop.timeMillis(), source));
                return;
            }
        }
    }

    // The office end of the trip. The actual point is used only if it is within the office radius.
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
