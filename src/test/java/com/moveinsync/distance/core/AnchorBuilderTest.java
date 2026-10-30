package com.moveinsync.distance.core;

import com.moveinsync.distance.model.Milestone;
import com.moveinsync.distance.model.RuleConfig;
import com.moveinsync.distance.model.TrackPoint;
import com.moveinsync.distance.model.TripEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Tests for AnchorBuilder.
class AnchorBuilderTest {

    private static final double OFFICE_LAT = 12.97;
    private static final double OFFICE_LON = 77.59;

    private final AnchorBuilder builder = new AnchorBuilder(RuleConfig.defaults());

    private TripEvidence tripWith(Milestone... milestones) {
        return new TripEvidence("t1", "client1", TripEvidence.Direction.LOGIN, List.of(), List.of(milestones), OFFICE_LAT, OFFICE_LON);
    }

    private Milestone milestone(Milestone.Type type, Double lat, Double lon, Long time,
                                Double fallbackLat, Double fallbackLon, Long fallbackTime) {
        return new Milestone(type, "e1", lat, lon, time, fallbackLat, fallbackLon, fallbackTime);
    }

    @Test
    void usesActualDropWhenValid() {
        List<TrackPoint> points = builder.build(tripWith(
                milestone(Milestone.Type.DROP, 12.9, 77.6, 1000L, 13.0, 77.7, 2000L)), List.of());
        assertEquals(12.9, points.get(0).latitude());
    }

    @Test
    void fallsBackWhenActualIsInvalid() {
        List<TrackPoint> points = builder.build(tripWith(
                milestone(Milestone.Type.DROP, null, null, 1000L, 13.0, 77.7, 2000L)), List.of());
        assertEquals(13.0, points.get(0).latitude());
        assertEquals(2000L, points.get(0).timeMillis());
    }

    @Test
    void zeroZeroLocationIsInvalid() {
        List<TrackPoint> points = builder.build(tripWith(
                milestone(Milestone.Type.PICKUP, 0.0, 0.0, 1000L, 13.0, 77.7, 2000L)), List.of());
        assertEquals(13.0, points.get(0).latitude());
    }

    @Test
    void noShowIsSkippedWhenFlagIsOff() {
        List<TrackPoint> points = builder.build(tripWith(
                milestone(Milestone.Type.NO_SHOW, 12.9, 77.6, 1000L, null, null, null)), List.of());
        assertTrue(points.isEmpty());
    }

    @Test
    void noShowIsAddedWhenFlagIsOn() {
        RuleConfig d = RuleConfig.defaults();
        RuleConfig flagOn = new RuleConfig(d.ruleVersion(), d.maxSpeedKmh(), d.maxAccuracyMeters(), d.shortGapSeconds(),
                d.parkedSpeedKmh(), d.maxRouteMeters(), d.roadFactor(), d.officeRadiusMeters(), true);
        List<TrackPoint> points = new AnchorBuilder(flagOn).build(tripWith(
                milestone(Milestone.Type.NO_SHOW, 12.9, 77.6, 1000L, null, null, null)), List.of());
        assertEquals(TrackPoint.Source.NO_SHOW_ANCHOR, points.get(0).source());
    }

    @Test
    void tripEndNearOfficeUsesActualPoint() {
        double about100MetersAway = OFFICE_LAT + 0.0009;
        List<TrackPoint> points = builder.build(tripWith(
                milestone(Milestone.Type.TRIP_END, about100MetersAway, OFFICE_LON, 5000L, null, null, null)), List.of());
        assertEquals(about100MetersAway, points.get(0).latitude());
    }

    @Test
    void tripEndFarFromOfficeUsesOfficeWithTripEndTime() {
        double about1KmAway = OFFICE_LAT + 0.009;
        List<TrackPoint> points = builder.build(tripWith(
                milestone(Milestone.Type.TRIP_END, about1KmAway, OFFICE_LON, 5000L, null, null, null)), List.of());
        assertEquals(OFFICE_LAT, points.get(0).latitude());
        assertEquals(5000L, points.get(0).timeMillis());
    }

    @Test
    void logoutTripAnchorsTheOfficeAtTheStartNotTheEnd() {
        TripEvidence logout = new TripEvidence("t1", "client1", TripEvidence.Direction.LOGOUT, List.of(), List.of(
                milestone(Milestone.Type.TRIP_START, OFFICE_LAT, OFFICE_LON, 1000L, null, null, null),
                milestone(Milestone.Type.TRIP_END, 12.8, 77.5, 9000L, null, null, null)), OFFICE_LAT, OFFICE_LON);
        List<TrackPoint> points = builder.build(logout, List.of());
        assertEquals(1, points.size());
        assertEquals(1000L, points.get(0).timeMillis());
    }

    @Test
    void pointsComeBackInTimeOrder() {
        List<TrackPoint> points = builder.build(tripWith(
                milestone(Milestone.Type.DROP, 12.9, 77.6, 9000L, null, null, null),
                milestone(Milestone.Type.PICKUP, 12.8, 77.5, 1000L, null, null, null)), List.of());
        assertEquals(1000L, points.get(0).timeMillis());
        assertEquals(9000L, points.get(1).timeMillis());
    }
}
