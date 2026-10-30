package com.moveinsync.distance.core;

import com.moveinsync.distance.model.Milestone;
import com.moveinsync.distance.model.Ping;
import com.moveinsync.distance.model.RuleConfig;
import com.moveinsync.distance.model.Segment;
import com.moveinsync.distance.model.SegmentRule;
import com.moveinsync.distance.model.TripEvidence;
import com.moveinsync.distance.model.TripResult;
import com.moveinsync.distance.routing.RouteBatch;
import com.moveinsync.distance.routing.Router;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The two worked examples from the problem statement, with their exact numbers.
class PdfExamplesTest {

    private static final double KM_NORTH = 0.008993;
    private static final double KM_EAST = 0.00922;
    private static final double OFFICE_LAT = 12.9;
    private static final double OFFICE_LON = 77.6;
    private static final long GAP_SECONDS = 22 * 60;

    // Each of the two real gaps is 7.1 km by road.
    private final Router router = requests -> new RouteBatch(Collections.nCopies(requests.size(), 7100L), 1);
    private final DistanceCalculator calculator = new DistanceCalculator(RuleConfig.defaults(), router);

    private double north(double km) {
        return OFFICE_LAT + km * KM_NORTH;
    }

    // Pings every 5 seconds at 36 km/h, heading north.
    private List<Ping> drive(double fromKm, double toKm, long startSeconds) {
        List<Ping> pings = new ArrayList<>();
        int steps = (int) Math.round((toKm - fromKm) / 0.05);
        for (int i = 0; i <= steps; i++) {
            pings.add(new Ping(north(fromKm + i * 0.05), OFFICE_LON, (startSeconds + i * 5L) * 1000, 10));
        }
        return pings;
    }

    private double naiveKm(List<Ping> pings) {
        double meters = 0;
        for (int i = 0; i + 1 < pings.size(); i++) {
            Ping a = pings.get(i);
            Ping b = pings.get(i + 1);
            meters += GeoMath.metersBetween(a.latitude(), a.longitude(), b.latitude(), b.longitude());
        }
        return meters / 1000;
    }

    private TripEvidence logoutTrip(List<Ping> pings, List<Milestone> milestones) {
        return new TripEvidence("t1", "client1", TripEvidence.Direction.LOGOUT, pings, milestones, List.of(), List.of(),
                OFFICE_LAT, OFFICE_LON);
    }

    @Test
    void thirtyOneKmLogoutTripWithA22MinuteGapReports30Point6Km() {
        // 9 km of GPS, a 22 minute gap that ends only 2.4 km further north, then 7 km of GPS.
        List<Ping> pings = new ArrayList<>(drive(0, 9, 0));
        long gapStart = 900;
        pings.addAll(drive(11.4, 18.4, gapStart + GAP_SECONDS));

        // Both drops happened inside the gap, 3 km east of the road and 60 seconds apart.
        double dropLon = OFFICE_LON + 3 * KM_EAST;
        Milestone start = new Milestone(Milestone.Type.TRIP_START, "cab", OFFICE_LAT, OFFICE_LON, 0L);
        Milestone drop1 = new Milestone(Milestone.Type.DROP, "e1", north(9.5), dropLon, (gapStart + 630) * 1000);
        Milestone drop2 = new Milestone(Milestone.Type.DROP, "e2", north(9.9), dropLon, (gapStart + 690) * 1000);

        assertEquals(18.4, naiveKm(pings), 0.05);

        TripResult result = calculator.calculate(logoutTrip(pings, List.of(start, drop1, drop2)));

        assertEquals(30.6, result.billableKm());
        assertEquals(TripResult.Status.OK, result.status());
        assertEquals(2, result.segments().stream().filter(s -> s.rule() == SegmentRule.ROUTED).count());
        assertEquals(1, result.providerCalls());
        for (Segment segment : result.segments()) {
            assertNotNull(segment.rule());
        }
    }

    @Test
    void burstOfPings9KmAwayFor4SecondsIsDroppedAndDistanceIsUnchanged() {
        List<Ping> clean = drive(0, 10, 0);
        long before = calculator.calculate(logoutTrip(clean, List.of())).totalMeters();

        double nineKm = 9 * KM_NORTH;
        assertTrue(GeoMath.speedKmh(GeoMath.metersBetween(0, 0, nineKm, 0), 4000) >= 8000);

        List<Ping> withBurst = new ArrayList<>(clean);
        for (int second = 1; second <= 4; second++) {
            withBurst.add(new Ping(north(5) + nineKm, OFFICE_LON, 500_000L + second * 800, 10));
        }
        TripResult result = calculator.calculate(logoutTrip(withBurst, List.of()));

        assertEquals(before, result.totalMeters());
        assertEquals(clean.size() - 1, result.segments().size());
    }
}
