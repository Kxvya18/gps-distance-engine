package com.moveinsync.distance.service;

import com.moveinsync.distance.core.DistanceCalculator;
import com.moveinsync.distance.dto.InvoiceResponse;
import com.moveinsync.distance.error.ConflictException;
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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Tests late pings, corrections, replays and automatic recovery against a real database layer.
@SpringBootTest
class RecomputeServiceTest {

    private static final int HIDDEN_PINGS = 400;
    private static final String TENANT = "client-a";

    @Autowired
    private TripService trips;
    @Autowired
    private RecomputeService recompute;
    @Autowired
    private DistanceCalculator calculator;
    @Autowired
    private FakeRoutingProvider provider;

    private final TripSimulator simulator = new TripSimulator(9);

    @AfterEach
    void providerBackUp() {
        provider.setDown(false);
    }

    private TripEvidence withPings(TripEvidence e, List<Ping> pings) {
        return new TripEvidence("trip-" + UUID.randomUUID(), TENANT, e.direction(), pings, e.milestones(),
                e.officeLatitude(), e.officeLongitude());
    }

    @Test
    void latePingsAfterBillingMakeASignedCorrectionAndLeaveTheInvoiceAlone() {
        TripEvidence full = simulator.generate(1).evidence();
        List<Ping> sorted = new ArrayList<>(full.pings());
        sorted.sort(java.util.Comparator.comparingLong(Ping::timestampMillis));
        List<Ping> late = sorted.subList(300, 300 + HIDDEN_PINGS);
        List<Ping> early = new ArrayList<>(sorted.subList(0, 300));
        early.addAll(sorted.subList(300 + HIDDEN_PINGS, sorted.size()));
        TripEvidence partial = withPings(full, early);

        TripResult first = trips.submit(partial);
        InvoiceResponse closed = trips.close(TENANT, partial.tripId(), false);
        assertEquals(first.totalMeters(), closed.billedMeters());

        TripResult updated = trips.addLatePings(TENANT, partial.tripId(), late);
        InvoiceResponse after = trips.invoice(TENANT, partial.tripId());

        long expectedDelta = updated.totalMeters() - first.totalMeters();
        assertTrue(expectedDelta != 0, "late pings should change the km");
        assertEquals(first.totalMeters(), after.billedMeters());
        assertEquals(1, after.corrections().size());
        assertEquals(expectedDelta, after.corrections().get(0).deltaMeters());
        assertEquals(updated.totalMeters(), after.currentMeters());
    }

    @Test
    void replayingTheSameLatePingsChangesNothing() {
        TripEvidence full = simulator.generate(2).evidence();
        List<Ping> sorted = new ArrayList<>(full.pings());
        sorted.sort(java.util.Comparator.comparingLong(Ping::timestampMillis));
        List<Ping> late = sorted.subList(100, 300);
        List<Ping> early = new ArrayList<>(sorted.subList(0, 100));
        early.addAll(sorted.subList(300, sorted.size()));
        TripEvidence partial = withPings(full, early);

        trips.submit(partial);
        trips.close(TENANT, partial.tripId(), false);
        trips.addLatePings(TENANT, partial.tripId(), late);
        int corrections = trips.invoice(TENANT, partial.tripId()).corrections().size();
        long calls = provider.callCount();

        trips.addLatePings(TENANT, partial.tripId(), late);

        assertEquals(corrections, trips.invoice(TENANT, partial.tripId()).corrections().size());
        assertEquals(calls, provider.callCount());
    }

    @Test
    void replayingTheSameTripReturnsTheSameResultWithoutProviderCalls() {
        TripEvidence trip = withPings(simulator.generate(3).evidence(), simulator.generate(3).evidence().pings());
        TripResult first = trips.submit(trip);
        long calls = provider.callCount();
        TripResult second = trips.submit(trip);
        assertEquals(first, second);
        assertEquals(calls, provider.callCount());
    }

    @Test
    void closingTwiceGivesTheSameInvoice() {
        TripEvidence trip = withPings(simulator.generate(4).evidence(), simulator.generate(4).evidence().pings());
        trips.submit(trip);
        assertEquals(trips.close(TENANT, trip.tripId(), false), trips.close(TENANT, trip.tripId(), false));
    }

    @Test
    void concurrentLatePingsAreAllKeptAndCorrectionsAddUp() throws Exception {
        TripEvidence full = simulator.generate(5).evidence();
        List<Ping> sorted = new ArrayList<>(full.pings());
        sorted.sort(java.util.Comparator.comparingLong(Ping::timestampMillis));

        int chunks = 6;
        int chunkSize = 100;
        List<List<Ping>> late = new ArrayList<>();
        for (int i = 0; i < chunks; i++) {
            late.add(new ArrayList<>(sorted.subList(100 + i * chunkSize, 100 + (i + 1) * chunkSize)));
        }
        List<Ping> early = new ArrayList<>(sorted.subList(0, 100));
        early.addAll(sorted.subList(100 + chunks * chunkSize, sorted.size()));
        TripEvidence partial = withPings(full, early);

        long billed = trips.submit(partial).totalMeters();
        trips.close(TENANT, partial.tripId(), false);

        ExecutorService pool = Executors.newFixedThreadPool(chunks);
        List<Future<TripResult>> posts = new ArrayList<>();
        for (List<Ping> chunk : late) {
            posts.add(pool.submit(() -> trips.addLatePings(TENANT, partial.tripId(), chunk)));
        }
        for (Future<TripResult> post : posts) {
            post.get();
        }
        pool.shutdown();

        TripResult finalResult = trips.get(TENANT, partial.tripId());
        long expected = calculator.calculate(withPings(full, sorted)).totalMeters();
        assertEquals(expected, finalResult.totalMeters(), "no late ping may be lost");

        InvoiceResponse invoice = trips.invoice(TENANT, partial.tripId());
        assertEquals(billed, invoice.billedMeters());
        assertEquals(finalResult.totalMeters(), invoice.currentMeters());
    }

    @Test
    void degradedTripCannotBeBilledAndRecoversAutomatically() {
        TripEvidence evidence = simulator.generate(6).evidence();
        TripEvidence trip = withPings(evidence, evidence.pings());

        // A fresh trip through a provider that is down. Answers cannot come from the cache
        // because the expected result is checked against the real distance afterwards.
        provider.setDown(true);
        TripResult degraded = trips.submit(shifted(trip));
        assertEquals(TripResult.Status.DEGRADED, degraded.status());
        assertThrows(ConflictException.class, () -> trips.close(TENANT, degraded.tripId(), false));

        provider.setDown(false);
        waitForBreakerCoolDown();
        int fixed = 0;
        for (int i = 0; i < 50 && fixed == 0; i++) {
            fixed = recompute.recomputeDegradedTrips();
        }
        assertTrue(fixed >= 1);
        assertEquals(TripResult.Status.OK, trips.get(TENANT, degraded.tripId()).status());
    }

    // Moves the whole trip so no cached route can answer it.
    private TripEvidence shifted(TripEvidence e) {
        List<Ping> moved = new ArrayList<>();
        for (Ping p : e.pings()) {
            moved.add(new Ping(p.latitude() + 0.3, p.longitude() + 0.3, p.timestampMillis(), p.accuracyMeters()));
        }
        return new TripEvidence(e.tripId(), e.tenantId(), e.direction(), moved, List.of(),
                e.officeLatitude(), e.officeLongitude());
    }

    private void waitForBreakerCoolDown() {
        try {
            Thread.sleep(5100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
