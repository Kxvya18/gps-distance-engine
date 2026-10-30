package com.moveinsync.distance.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Tests for GeoMath.
class GeoMathTest {

    @Test
    void oneDegreeOfLatitudeIsAbout111Km() {
        assertEquals(111_195, GeoMath.metersBetween(12.0, 77.0, 13.0, 77.0), 1.0);
    }

    @Test
    void samePointIsZero() {
        assertEquals(0.0, GeoMath.metersBetween(12.9, 77.6, 12.9, 77.6));
    }

    @Test
    void noTimeAndNoMovementIsZeroSpeed() {
        assertEquals(0.0, GeoMath.speedKmh(0, 0));
    }

    @Test
    void movementInNoTimeIsInfiniteSpeed() {
        assertEquals(Double.POSITIVE_INFINITY, GeoMath.speedKmh(10, 0));
    }

    @Test
    void nineKmInFourSecondsIsAbout8100Kmh() {
        assertEquals(8100.0, GeoMath.speedKmh(9000, 4000), 0.001);
    }
}
