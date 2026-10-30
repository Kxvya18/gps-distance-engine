package com.moveinsync.distance.core;

import com.moveinsync.distance.model.RuleConfig;
import com.moveinsync.distance.model.Segment;
import com.moveinsync.distance.model.SegmentRule;
import com.moveinsync.distance.model.TrackPoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Tests for each billing rule.
class SegmentRuleEngineTest {

    private static final double ONE_KM_IN_DEGREES = 0.008993;

    private final SegmentRuleEngine rules = new SegmentRuleEngine(RuleConfig.defaults());

    private TrackPoint point(double kmNorth, long seconds) {
        return new TrackPoint(12.9 + kmNorth * ONE_KM_IN_DEGREES, 77.6, seconds * 1000, TrackPoint.Source.PING);
    }

    @Test
    void shortGapAtPlausibleSpeedIsAerial() {
        TrackPoint from = point(0, 0);
        TrackPoint to = point(0.5, 60);
        assertFalse(rules.needsRoute(from, to));
        Segment segment = rules.localSegment(from, to);
        assertEquals(SegmentRule.SHORT_GAP_AERIAL, segment.rule());
        assertEquals(500, segment.distanceMeters(), 1);
    }

    @Test
    void exactly70SecondsIsNotAShortGap() {
        assertTrue(rules.needsRoute(point(0, 0), point(0.5, 70)));
    }

    @Test
    void longGapUnderFiveKmhIsParked() {
        TrackPoint from = point(0, 0);
        TrackPoint to = point(0.1, 600);
        assertFalse(rules.needsRoute(from, to));
        assertEquals(SegmentRule.PARKED, rules.localSegment(from, to).rule());
    }

    @Test
    void longMovingGapNeedsARoute() {
        assertTrue(rules.needsRoute(point(0, 0), point(5, 600)));
    }

    @Test
    void shortGapAtImpossibleSpeedNeedsARoute() {
        assertTrue(rules.needsRoute(point(0, 0), point(5, 10)));
    }

    @Test
    void sameTimestampWithinGpsErrorIsAShortGapNotARoute() {
        TrackPoint from = point(0, 100);
        TrackPoint to = point(0.02, 100);
        assertFalse(rules.needsRoute(from, to));
        assertEquals(SegmentRule.SHORT_GAP_AERIAL, rules.localSegment(from, to).rule());
    }

    @Test
    void goodRouteIsUsed() {
        Segment segment = rules.routedSegment(point(0, 0), point(5, 600), 6500L);
        assertEquals(SegmentRule.ROUTED, segment.rule());
        assertEquals(6500, segment.distanceMeters());
    }

    @Test
    void routeOver100KmIsRejectedAndEstimated() {
        Segment segment = rules.routedSegment(point(0, 0), point(5, 600), 100_001L);
        assertEquals(SegmentRule.REJECTED_DISTANCE, segment.rule());
        assertEquals(7000, segment.distanceMeters(), 2);
    }

    @Test
    void tooFastRouteWithTooFastEstimateIsRejectedAndUsesAerial() {
        Segment segment = rules.routedSegment(point(0, 0), point(5, 100), 6000L);
        assertEquals(SegmentRule.REJECTED_SPEED, segment.rule());
        assertEquals(5000, segment.distanceMeters(), 2);
    }

    @Test
    void tooFastRouteWithPlausibleEstimateIsKept() {
        Segment segment = rules.routedSegment(point(0, 0), point(5, 300), 12_000L);
        assertEquals(SegmentRule.ROUTED, segment.rule());
    }

    @Test
    void noAnswerUsesStraightLineTimes1Point4() {
        Segment segment = rules.routedSegment(point(0, 0), point(5, 600), null);
        assertEquals(SegmentRule.PROVIDER_DOWN_ESTIMATE, segment.rule());
        assertEquals(7000, segment.distanceMeters(), 2);
    }
}
