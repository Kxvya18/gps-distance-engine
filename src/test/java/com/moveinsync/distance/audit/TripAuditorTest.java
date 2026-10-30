package com.moveinsync.distance.audit;

import com.moveinsync.distance.core.DistanceCalculator;
import com.moveinsync.distance.model.RuleConfig;
import com.moveinsync.distance.model.Segment;
import com.moveinsync.distance.model.SegmentRule;
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
