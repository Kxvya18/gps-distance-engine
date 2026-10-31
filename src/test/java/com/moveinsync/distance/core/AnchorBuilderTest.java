package com.moveinsync.distance.core;

import com.moveinsync.distance.model.AdHocRider;
import com.moveinsync.distance.model.Milestone;
import com.moveinsync.distance.model.PlannedStop;
import com.moveinsync.distance.model.RuleConfig;
import com.moveinsync.distance.model.TrackPoint;
import com.moveinsync.distance.model.TripEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnchorBuilderTest {

    private static final double OFFICE_LAT = 12.97;
    private static final double OFFICE_LON = 77.59;
    private static final PlannedStop PLANNED = new PlannedStop("e1", 13.0, 77.7, 2000L);
    private static final AdHocRider AD_HOC = new AdHocRider("e2", 13.1, 77.8, 3000L);

    private final AnchorBuilder builder = new AnchorBuilder(RuleConfig.defaults());

    private TripEvidence tripWith(TripEvidence.Direction direction, Milestone... milestones) {
        return new TripEvidence("t1", "client1", direction, List.of(), List.of(milestones),
                List.of(PLANNED), List.of(AD_HOC), OFFICE_LAT, OFFICE_LON);
    }

    private TripEvidence loginTrip(Milestone... milestones) {
        return tripWith(TripEvidence.Direction.LOGIN, milestones);
    }

    private Milestone milestone(Milestone.Type type, String employee, Double lat, Double lon, Long time) {
        return new Milestone(type, employee, lat, lon, time);
    }

    @Test
    void usesActualDropWhenValid() {
        List<TrackPoint> points = builder.build(loginTrip(
                milestone(Milestone.Type.DROP, "e1", 12.9, 77.6, 1000L)), List.of());
        assertEquals(12.9, points.get(0).latitude());
    }

    @Test
    void plannedRiderFallsBackToPlannedLocationAndTime() {
        List<TrackPoint> points = builder.build(loginTrip(
                milestone(Milestone.Type.DROP, "e1", null, null, 1000L)), List.of());
        assertEquals(PLANNED.latitude(), points.get(0).latitude());
        assertEquals(PLANNED.timeMillis(), points.get(0).timeMillis());
    }

    @Test
    void adHocRiderFallsBackToProfileHomeAndSignInTime() {
        List<TrackPoint> points = builder.build(loginTrip(
                milestone(Milestone.Type.PICKUP, "e2", null, null, null)), List.of());
        assertEquals(AD_HOC.homeLatitude(), points.get(0).latitude());
        assertEquals(AD_HOC.signInTimeMillis(), points.get(0).timeMillis());
    }

    @Test
    void missingTimeMakesTheActualPointInvalid() {
        List<TrackPoint> points = builder.build(loginTrip(
                milestone(Milestone.Type.DROP, "e1", 12.9, 77.6, null)), List.of());
        assertEquals(PLANNED.latitude(), points.get(0).latitude());
    }

    @Test
    void zeroZeroLocationIsInvalid() {
        List<TrackPoint> points = builder.build(loginTrip(
                milestone(Milestone.Type.PICKUP, "e1", 0.0, 0.0, 1000L)), List.of());
        assertEquals(PLANNED.latitude(), points.get(0).latitude());
    }

    @Test
    void outOfRangeLocationIsInvalid() {
        List<TrackPoint> points = builder.build(loginTrip(
                milestone(Milestone.Type.PICKUP, "e1", 95.0, 77.6, 1000L)), List.of());
        assertEquals(PLANNED.latitude(), points.get(0).latitude());
    }

    @Test
    void locationsAtTheEdgeOfTheMapAreValid() {
        double[][] edges = {{90.0, 77.6}, {-90.0, 77.6}, {12.9, 180.0}, {12.9, -180.0}};
        for (double[] edge : edges) {
            List<TrackPoint> points = builder.build(loginTrip(
                    milestone(Milestone.Type.DROP, "e1", edge[0], edge[1], 1000L)), List.of());
            assertEquals(edge[0], points.get(0).latitude());
        }
    }

    @Test
    void locationsJustOffTheMapAreInvalid() {
        double[][] edges = {{90.01, 77.6}, {-90.01, 77.6}, {12.9, 180.01}, {12.9, -180.01}};
        for (double[] edge : edges) {
            List<TrackPoint> points = builder.build(loginTrip(
                    milestone(Milestone.Type.DROP, "e1", edge[0], edge[1], 1000L)), List.of());
            assertEquals(PLANNED.latitude(), points.get(0).latitude());
        }
    }

    @Test
    void zeroLatitudeAloneIsStillValid() {
        List<TrackPoint> points = builder.build(loginTrip(
                milestone(Milestone.Type.DROP, "e1", 0.0, 77.6, 1000L)), List.of());
        assertEquals(0.0, points.get(0).latitude());
    }

    @Test
    void riderWithNoValidPointAndNoFallbackAddsNothing() {
        assertTrue(builder.build(loginTrip(
                milestone(Milestone.Type.DROP, "unknown", null, null, null)), List.of()).isEmpty());
    }

    @Test
    void noShowIsSkippedWhenFlagIsOff() {
        assertTrue(builder.build(loginTrip(
                milestone(Milestone.Type.NO_SHOW, "e1", 12.9, 77.6, 1000L)), List.of()).isEmpty());
    }

    private AnchorBuilder withNoShowsOn() {
        RuleConfig d = RuleConfig.defaults();
        return new AnchorBuilder(new RuleConfig(d.ruleVersion(), d.maxSpeedKmh(), d.maxAccuracyMeters(), d.shortGapSeconds(),
                d.parkedSpeedKmh(), d.maxRouteMeters(), d.roadFactor(), d.officeRadiusMeters(), true));
    }

    @Test
    void noShowLocationIsAddedWhenFlagIsOn() {
        List<TrackPoint> points = withNoShowsOn().build(loginTrip(
                milestone(Milestone.Type.NO_SHOW, "e1", 12.9, 77.6, 1000L)), List.of());
        assertEquals(TrackPoint.Source.NO_SHOW_ANCHOR, points.get(0).source());
        assertEquals(12.9, points.get(0).latitude());
    }

    @Test
    void invalidNoShowLocationFallsBackToPlannedLocation() {
        List<TrackPoint> points = withNoShowsOn().build(loginTrip(
                milestone(Milestone.Type.NO_SHOW, "e1", null, null, 1000L)), List.of());
        assertEquals(TrackPoint.Source.NO_SHOW_ANCHOR, points.get(0).source());
        assertEquals(PLANNED.latitude(), points.get(0).latitude());
    }

    @Test
    void tripEndWithin400MetersUsesActualPoint() {
        double about390MetersAway = OFFICE_LAT + 0.0035;
        List<TrackPoint> points = builder.build(loginTrip(
                milestone(Milestone.Type.TRIP_END, "cab", about390MetersAway, OFFICE_LON, 5000L)), List.of());
        assertEquals(about390MetersAway, points.get(0).latitude());
    }

    @Test
    void tripEndBeyond400MetersUsesOfficeWithTripEndTime() {
        double about410MetersAway = OFFICE_LAT + 0.0037;
        List<TrackPoint> points = builder.build(loginTrip(
                milestone(Milestone.Type.TRIP_END, "cab", about410MetersAway, OFFICE_LON, 5000L)), List.of());
        assertEquals(OFFICE_LAT, points.get(0).latitude());
        assertEquals(OFFICE_LON, points.get(0).longitude());
        assertEquals(5000L, points.get(0).timeMillis());
    }

    @Test
    void invalidTripEndUsesOfficeWithTripEndTime() {
        List<TrackPoint> points = builder.build(loginTrip(
                milestone(Milestone.Type.TRIP_END, "cab", null, null, 5000L)), List.of());
        assertEquals(OFFICE_LAT, points.get(0).latitude());
        assertEquals(5000L, points.get(0).timeMillis());
    }

    @Test
    void logoutTripAnchorsTheOfficeAtTheStartNotTheEnd() {
        List<TrackPoint> points = builder.build(tripWith(TripEvidence.Direction.LOGOUT,
                milestone(Milestone.Type.TRIP_START, "cab", OFFICE_LAT, OFFICE_LON, 1000L),
                milestone(Milestone.Type.TRIP_END, "cab", 12.8, 77.5, 9000L)), List.of());
        assertEquals(1, points.size());
        assertEquals(1000L, points.get(0).timeMillis());
    }

    @Test
    void pointsComeBackInTimeOrder() {
        List<TrackPoint> points = builder.build(loginTrip(
                milestone(Milestone.Type.DROP, "e1", 12.9, 77.6, 9000L),
                milestone(Milestone.Type.PICKUP, "e2", 12.8, 77.5, 1000L)), List.of());
        assertEquals(1000L, points.get(0).timeMillis());
        assertEquals(9000L, points.get(1).timeMillis());
    }
}
