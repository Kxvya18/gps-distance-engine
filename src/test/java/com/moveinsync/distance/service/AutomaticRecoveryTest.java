package com.moveinsync.distance.service;

import com.moveinsync.distance.model.Ping;
import com.moveinsync.distance.model.TripEvidence;
import com.moveinsync.distance.model.TripResult;
import com.moveinsync.distance.routing.FakeRoutingProvider;
import com.moveinsync.distance.simulation.TripSimulator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Failure test with the real scheduler running: nothing but the provider recovering fixes the trip.
@SpringBootTest(properties = "app.recompute-delay-millis=200")
class AutomaticRecoveryTest {

    private static final long MAX_WAIT_MILLIS = 20_000;

    @Autowired
    private TripService trips;
    @Autowired
    private FakeRoutingProvider provider;

    @AfterEach
    void providerBackUp() {
        provider.setDown(false);
    }

    @Test
    void degradedTripIsRecomputedWithoutAnyRequest() throws Exception {
        TripEvidence e = new TripSimulator(11).generate(1).evidence();
        List<Ping> moved = new ArrayList<>();
        for (Ping p : e.pings()) {
            moved.add(new Ping(p.latitude() + 0.7, p.longitude(), p.timestampMillis(), p.accuracyMeters()));
        }
        TripEvidence trip = new TripEvidence("auto-recovery", "client-a", e.direction(), moved, List.of(), List.of(), List.of(),
                e.officeLatitude(), e.officeLongitude());

        provider.setDown(true);
        assertEquals(TripResult.Status.DEGRADED, trips.submit(trip).status());
        provider.setDown(false);

        long deadline = System.currentTimeMillis() + MAX_WAIT_MILLIS;
        TripResult.Status status = TripResult.Status.DEGRADED;
        while (status == TripResult.Status.DEGRADED && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            status = trips.get("client-a", "auto-recovery").status();
        }
        assertEquals(TripResult.Status.OK, status);
    }
}
