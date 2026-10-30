package com.moveinsync.distance.core;

import com.moveinsync.distance.model.AdHocRider;
import com.moveinsync.distance.model.Milestone;
import com.moveinsync.distance.model.Ping;
import com.moveinsync.distance.model.PlannedStop;
import com.moveinsync.distance.model.TripEvidence;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Tests that the fingerprint only changes when the evidence really changes.
class EvidenceHasherTest {

    private static final Ping A = new Ping(12.9, 77.6, 0, 10);
    private static final Ping B = new Ping(12.91, 77.6, 5000, 10);
    private static final Milestone DROP = new Milestone(Milestone.Type.DROP, "e1", 12.9, 77.6, 0L);
    private static final PlannedStop STOP = new PlannedStop("e1", 12.9, 77.6, 0);
    private static final AdHocRider RIDER = new AdHocRider("e2", 12.9, 77.6, 0);

    private TripEvidence trip(List<Ping> pings, List<PlannedStop> stops, List<AdHocRider> riders) {
        return new TripEvidence("t1", "c1", TripEvidence.Direction.LOGIN, pings, List.of(DROP), stops, riders, 12.9, 77.6);
    }

    private String hash(TripEvidence trip) {
        return EvidenceHasher.hash(trip, 1);
    }

    @Test
    void hashIsAFullSha256Value() {
        assertTrue(hash(trip(List.of(A, B), List.of(), List.of())).matches("[0-9a-f]{64}"));
    }

    @Test
    void orderAndDuplicatesDoNotChangeTheHash() {
        List<Ping> shuffled = new ArrayList<>(List.of(B, A, B, A));
        Collections.shuffle(shuffled);
        assertEquals(hash(trip(List.of(A, B), List.of(), List.of())), hash(trip(shuffled, List.of(), List.of())));
    }

    @Test
    void anExtraPingChangesTheHash() {
        assertNotEquals(hash(trip(List.of(A), List.of(), List.of())), hash(trip(List.of(A, B), List.of(), List.of())));
    }

    @Test
    void plannedStopsAndRidersChangeTheHash() {
        String base = hash(trip(List.of(A), List.of(), List.of()));
        assertNotEquals(base, hash(trip(List.of(A), List.of(STOP), List.of())));
        assertNotEquals(base, hash(trip(List.of(A), List.of(), List.of(RIDER))));
    }

    @Test
    void ruleVersionChangesTheHash() {
        TripEvidence trip = trip(List.of(A), List.of(), List.of());
        assertNotEquals(EvidenceHasher.hash(trip, 1), EvidenceHasher.hash(trip, 2));
    }
}
