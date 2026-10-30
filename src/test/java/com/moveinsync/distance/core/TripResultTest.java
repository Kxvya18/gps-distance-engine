package com.moveinsync.distance.core;

import com.moveinsync.distance.model.TripResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Tests the single rounding step: half up to 0.1 km, from whole metres.
class TripResultTest {

    private double km(long meters) {
        return new TripResult("t", 1, meters, List.of(), TripResult.Status.OK, 0).billableKm();
    }

    @Test
    void roundsHalfUpToOneDecimal() {
        assertEquals(30.6, km(30_550));
        assertEquals(30.5, km(30_549));
        assertEquals(30.6, km(30_600));
        assertEquals(0.0, km(0));
    }
}
