package com.moveinsync.distance.core;

import com.moveinsync.distance.model.Milestone;
import com.moveinsync.distance.model.Ping;
import com.moveinsync.distance.model.RuleConfig;
import com.moveinsync.distance.model.Segment;
import com.moveinsync.distance.model.SegmentRule;
import com.moveinsync.distance.model.TripEvidence;
import com.moveinsync.distance.model.TripResult;
import com.moveinsync.distance.routing.CircuitBreaker;
import com.moveinsync.distance.routing.FakeRoutingProvider;
import com.moveinsync.distance.routing.ResilientRouter;
import com.moveinsync.distance.routing.RouteBatch;
import com.moveinsync.distance.routing.RouteCache;
import com.moveinsync.distance.routing.Router;
import com.moveinsync.distance.simulation.TripSimulator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Tests the full trip, and that repeat runs match exactly.
class DistanceCalculatorTest {

    private static final double ONE_KM_IN_DEGREES = 0.008993;
    private static final double OFFICE_LAT = 12.9;
    private static final double OFFICE_LON = 77.6;

    // Every gap has a road 1.3 times the straight line. Keeps the expected numbers easy to read.
    private final Router router = requests -> {
        List<Long> answers = new ArrayList<>();
        for (var r : requests) {
            double aerial = GeoMath.metersBetween(r.fromLatitude(), r.fromLongitude(), r.toLatitude(), r.toLongitude());
            answers.add(Math.round(aerial * 1.3));
        }
        return new RouteBatch(answers, 1);
    };

    private final DistanceCalculator calculator = new DistanceCalculator(RuleConfig.defaults(), router);

    private double north(double km) {
        return OFFICE_LAT + km * ONE_KM_IN_DEGREES;
    }

    // Cab drives north at 36 km/h, one ping every 5 seconds, between two kilometre marks.
    private List<Ping> drive(double startKm, double endKm) {
        List<Ping> pings = new ArrayList<>();
        for (double km = startKm; km <= endKm + 0.0001; km += 0.05) {
            pings.add(new Ping(north(km), OFFICE_LON, Math.round(km * 100_000), 10));
        }
        return pings;
    }

    private Milestone drop(double km) {
        return new Milestone(Milestone.Type.DROP, "e" + km, north(km), OFFICE_LON, Math.round(km * 100_000));
    }

    private TripEvidence trip(List<Ping> pings, Milestone... milestones) {
        return new TripEvidence("t1", "client1", TripEvidence.Direction.LOGOUT, pings, List.of(milestones), List.of(), List.of(), OFFICE_LAT, OFFICE_LON);
    }

    private long countRule(TripResult result, SegmentRule rule) {
        return result.segments().stream().filter(s -> s.rule() == rule).count();
    }

    @Test
    void fullGpsTripIsAllShortGaps() {
        TripResult result = calculator.calculate(trip(drive(0, 10)));
        assertEquals(10_000, result.totalMeters(), 5);
        assertEquals(TripResult.Status.OK, result.status());
        assertEquals(0, result.providerCalls());
    }

    @Test
    void anchorsSplitTheTunnelGapAndOnlyRealGapsAreRouted() {
        List<Ping> pings = new ArrayList<>(drive(0, 10));
        pings.addAll(drive(22, 31));
        TripResult result = calculator.calculate(trip(pings, drop(16), drop(16.5)));

        assertEquals(2, countRule(result, SegmentRule.ROUTED));
        // 10 + 6 x 1.3 + 0.5 aerial + 5.5 x 1.3 + 9 km, with the middle hop under 70 seconds.
        assertEquals(34_450, result.totalMeters(), 10);
        assertEquals(TripResult.Status.OK, result.status());
    }

    @Test
    void burstOfBadPingsLeavesTheDistanceUnchanged() {
        long before = calculator.calculate(trip(drive(0, 10))).totalMeters();
        List<Ping> pings = new ArrayList<>(drive(0, 10));
        for (int i = 0; i < 4; i++) {
            pings.add(new Ping(north(5) + 0.081, OFFICE_LON, 500_000 + i * 1000 + 500, 10));
        }
        assertEquals(before, calculator.calculate(trip(pings)).totalMeters());
    }

    @Test
    void shuffledAndDuplicatedInputGivesTheSameSegments() {
        List<Ping> pings = new ArrayList<>(drive(0, 10));
        pings.addAll(drive(22, 31));
        TripResult first = calculator.calculate(trip(pings, drop(16)));

        List<Ping> shuffled = new ArrayList<>(pings);
        shuffled.addAll(pings.subList(0, 50));
        Collections.shuffle(shuffled, new java.util.Random(7));
        TripResult second = calculator.calculate(trip(shuffled, drop(16)));

        assertEquals(first, second);
    }

    @Test
    void twoSeparateRunsAgreeToTheMetreWhetherOrNotAnswersWereCached() {
        TripSimulator simulator = new TripSimulator(3);
        DistanceCalculator shared = freshEngine();
        for (int i = 0; i < 100; i++) {
            TripEvidence evidence = simulator.generate(i).evidence();
            shared.calculate(evidence);
            TripResult fromCache = shared.calculate(evidence);
            TripResult fromProvider = freshEngine().calculate(evidence);

            assertEquals(0, fromCache.providerCalls());
            assertEquals(fromProvider.totalMeters(), fromCache.totalMeters());
            assertEquals(fromProvider.segments(), fromCache.segments());
            assertEquals(RuleConfig.defaults().ruleVersion(), fromCache.ruleVersion());
        }
    }

    private DistanceCalculator freshEngine() {
        return new DistanceCalculator(RuleConfig.defaults(), new ResilientRouter(new FakeRoutingProvider(0, 100),
                new RouteCache(10_000, 60_000, System::currentTimeMillis),
                new CircuitBreaker(5, 1000, System::currentTimeMillis), 25, 8));
    }

    @Test
    void noRouterAnswerMarksTheTripDegraded() {
        Router down = requests -> new RouteBatch(Collections.nCopies(requests.size(), null), 0);
        List<Ping> pings = new ArrayList<>(drive(0, 10));
        pings.addAll(drive(22, 31));
        TripResult result = new DistanceCalculator(RuleConfig.defaults(), down).calculate(trip(pings));

        assertEquals(TripResult.Status.DEGRADED, result.status());
        assertEquals(1, countRule(result, SegmentRule.PROVIDER_DOWN_ESTIMATE));
    }

    @Test
    void partialRouterAnswerKeepsTheRealRoutesAndMarksTheTripDegraded() {
        Router answersFirstGapOnly = requests -> {
            List<Long> answers = new ArrayList<>(Collections.nCopies(requests.size(), null));
            answers.set(0, 7000L);
            return new RouteBatch(answers, 1);
        };
        List<Ping> pings = new ArrayList<>(drive(0, 10));
        pings.addAll(drive(22, 31));
        TripResult result = new DistanceCalculator(RuleConfig.defaults(), answersFirstGapOnly).calculate(trip(pings, drop(16)));

        assertEquals(TripResult.Status.DEGRADED, result.status());
        assertEquals(1, countRule(result, SegmentRule.ROUTED));
        assertEquals(1, countRule(result, SegmentRule.PROVIDER_DOWN_ESTIMATE));
    }

    @Test
    void tripWithNoUsablePointsGoesToReview() {
        TripResult result = calculator.calculate(trip(List.of()));
        assertEquals(0, result.totalMeters());
        assertEquals(TripResult.Status.NEEDS_REVIEW, result.status());
    }

    @Test
    void totalIsTheExactSumOfTheSegments() {
        TripResult result = calculator.calculate(trip(drive(0, 10)));
        assertEquals(result.totalMeters(), result.segments().stream().mapToLong(Segment::distanceMeters).sum());
    }
}
