package com.moveinsync.distance.audit;

import com.moveinsync.distance.core.DistanceCalculator;
import com.moveinsync.distance.model.RuleConfig;
import com.moveinsync.distance.model.Segment;
import com.moveinsync.distance.model.SegmentRule;
import com.moveinsync.distance.model.TrackPoint;
import com.moveinsync.distance.model.TripResult;
import com.moveinsync.distance.routing.CircuitBreaker;
import com.moveinsync.distance.routing.FakeRoutingProvider;
import com.moveinsync.distance.routing.ResilientRouter;
import com.moveinsync.distance.routing.RouteCache;
import com.moveinsync.distance.simulation.TripSimulator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Tests for TripAuditor.
class TripAuditorTest {

    private final RuleConfig config = RuleConfig.defaults();
    private final TripAuditor auditor = new TripAuditor(config);
    private final FakeRoutingProvider provider = new FakeRoutingProvider(0, 100);
    private final DistanceCalculator calculator = new DistanceCalculator(config, new ResilientRouter(provider,
            new RouteCache(10_000, 60_000, System::currentTimeMillis),
            new CircuitBreaker(5, 1000, System::currentTimeMillis), 25, 8));
    private final TripSimulator simulator = new TripSimulator(1);

    private TripResult sampleTrip() {
        return calculator.calculate(simulator.generate(3).evidence());
    }

    private int firstOf(TripResult result, SegmentRule rule) {
        for (int i = 0; i < result.segments().size(); i++) {
            if (result.segments().get(i).rule() == rule) {
                return i;
            }
        }
        throw new AssertionError("no segment with rule " + rule);
    }

    private TripResult replaceSegment(TripResult result, int index, Segment changed) {
        List<Segment> segments = new ArrayList<>(result.segments());
        long total = result.totalMeters() - segments.get(index).distanceMeters() + changed.distanceMeters();
        segments.set(index, changed);
        return new TripResult(result.tripId(), result.ruleVersion(), total, segments, result.status(), result.providerCalls());
    }

    @Test
    void realEngineResultsPassTheAudit() {
        for (int i = 0; i < 200; i++) {
            List<String> problems = auditor.audit(calculator.calculate(simulator.generate(i).evidence()));
            assertTrue(problems.isEmpty(), "trip " + i + ": " + problems);
        }
    }

    private static final double ONE_KM_IN_DEGREES = 0.008993;

    private TrackPoint point(double kmNorth, long seconds) {
        return new TrackPoint(12.9 + kmNorth * ONE_KM_IN_DEGREES, 77.6, seconds * 1000, TrackPoint.Source.PING);
    }

    // One segment from km 0 at time 0, to the given place and time.
    private List<String> auditOne(double toKm, long seconds, long meters, SegmentRule rule, TripResult.Status status) {
        Segment segment = new Segment(point(0, 0), point(toKm, seconds), meters, rule);
        return auditor.audit(new TripResult("t", 1, meters, List.of(segment), status, 0));
    }

    @Test
    void correctSegmentsOfEveryRulePass() {
        assertTrue(auditOne(0.5, 60, 500, SegmentRule.SHORT_GAP_AERIAL, TripResult.Status.OK).isEmpty());
        assertTrue(auditOne(0.1, 600, 100, SegmentRule.PARKED, TripResult.Status.OK).isEmpty());
        assertTrue(auditOne(5, 600, 6500, SegmentRule.ROUTED, TripResult.Status.OK).isEmpty());
        assertTrue(auditOne(5, 600, 7000, SegmentRule.PROVIDER_DOWN_ESTIMATE, TripResult.Status.DEGRADED).isEmpty());
        assertTrue(auditOne(5, 600, 7000, SegmentRule.REJECTED_DISTANCE, TripResult.Status.NEEDS_REVIEW).isEmpty());
        assertTrue(auditOne(5, 100, 5000, SegmentRule.REJECTED_SPEED, TripResult.Status.NEEDS_REVIEW).isEmpty());
    }

    @Test
    void catchesAWrongShortGapLabel() {
        assertFalse(auditOne(0.5, 100, 500, SegmentRule.SHORT_GAP_AERIAL, TripResult.Status.OK).isEmpty());
    }

    @Test
    void catchesAParkedSegmentThatWasMoving() {
        assertFalse(auditOne(5, 600, 5000, SegmentRule.PARKED, TripResult.Status.OK).isEmpty());
    }

    @Test
    void catchesAWrongParkedDistance() {
        assertFalse(auditOne(0.1, 600, 300, SegmentRule.PARKED, TripResult.Status.OK).isEmpty());
    }

    @Test
    void catchesARouteOverTheLimit() {
        assertFalse(auditOne(5, 600, 100_001, SegmentRule.ROUTED, TripResult.Status.OK).isEmpty());
    }

    @Test
    void catchesARouteShorterThanTheStraightLine() {
        assertFalse(auditOne(5, 600, 4000, SegmentRule.ROUTED, TripResult.Status.OK).isEmpty());
    }

    @Test
    void catchesARouteThatShouldHaveBeenParked() {
        assertFalse(auditOne(0.1, 600, 300, SegmentRule.ROUTED, TripResult.Status.OK).isEmpty());
    }

    @Test
    void catchesAWrongEstimate() {
        assertFalse(auditOne(5, 600, 9000, SegmentRule.PROVIDER_DOWN_ESTIMATE, TripResult.Status.DEGRADED).isEmpty());
    }

    @Test
    void catchesAWrongRejectedDistance() {
        assertFalse(auditOne(5, 600, 9000, SegmentRule.REJECTED_DISTANCE, TripResult.Status.NEEDS_REVIEW).isEmpty());
    }

    @Test
    void catchesARejectedRouteOnAnOkTrip() {
        assertFalse(auditOne(5, 600, 7000, SegmentRule.REJECTED_DISTANCE, TripResult.Status.OK).isEmpty());
        assertFalse(auditOne(5, 100, 5000, SegmentRule.REJECTED_SPEED, TripResult.Status.OK).isEmpty());
    }

    @Test
    void catchesASpeedRejectionWhenTheEstimateWasFine() {
        assertFalse(auditOne(5, 600, 5000, SegmentRule.REJECTED_SPEED, TripResult.Status.NEEDS_REVIEW).isEmpty());
    }

    @Test
    void catchesAWrongSpeedRejectionDistance() {
        assertFalse(auditOne(5, 100, 7000, SegmentRule.REJECTED_SPEED, TripResult.Status.NEEDS_REVIEW).isEmpty());
    }

    @Test
    void catchesTimeRunningBackwards() {
        Segment segment = new Segment(point(0, 100), point(0, 50), 0, SegmentRule.SHORT_GAP_AERIAL);
        assertFalse(auditor.audit(new TripResult("t", 1, 0, List.of(segment), TripResult.Status.OK, 0)).isEmpty());
    }

    @Test
    void catchesSegmentsThatDoNotConnect() {
        Segment first = new Segment(point(0, 0), point(0.1, 10), 100, SegmentRule.SHORT_GAP_AERIAL);
        Segment second = new Segment(point(0.5, 20), point(0.6, 30), 100, SegmentRule.SHORT_GAP_AERIAL);
        assertFalse(auditor.audit(new TripResult("t", 1, 200, List.of(first, second), TripResult.Status.OK, 0)).isEmpty());
    }

    @Test
    void samePlaceAtTheSameInstantIsAValidShortGap() {
        assertTrue(auditOne(0, 0, 0, SegmentRule.SHORT_GAP_AERIAL, TripResult.Status.OK).isEmpty());
    }

    @Test
    void catchesATotalThatIsNotTheSum() {
        TripResult r = sampleTrip();
        TripResult bad = new TripResult(r.tripId(), r.ruleVersion(), r.totalMeters() + 100, r.segments(), r.status(), r.providerCalls());
        assertFalse(auditor.audit(bad).isEmpty());
    }

    @Test
    void catchesAChangedShortGapDistance() {
        TripResult r = sampleTrip();
        int i = firstOf(r, SegmentRule.SHORT_GAP_AERIAL);
        Segment s = r.segments().get(i);
        Segment changed = new Segment(s.from(), s.to(), s.distanceMeters() + 500, s.rule());
        assertFalse(auditor.audit(replaceSegment(r, i, changed)).isEmpty());
    }

    @Test
    void catchesAWrongRuleLabel() {
        TripResult r = sampleTrip();
        int i = firstOf(r, SegmentRule.ROUTED);
        Segment s = r.segments().get(i);
        Segment changed = new Segment(s.from(), s.to(), s.distanceMeters(), SegmentRule.SHORT_GAP_AERIAL);
        assertFalse(auditor.audit(replaceSegment(r, i, changed)).isEmpty());
    }

    @Test
    void catchesAnEstimateOnATripNotMarkedDegraded() {
        TripResult r = sampleTrip();
        assertEquals(TripResult.Status.OK, r.status());
        int i = firstOf(r, SegmentRule.ROUTED);
        Segment s = r.segments().get(i);
        Segment changed = new Segment(s.from(), s.to(), s.distanceMeters(), SegmentRule.PROVIDER_DOWN_ESTIMATE);
        assertFalse(auditor.audit(replaceSegment(r, i, changed)).isEmpty());
    }
}
