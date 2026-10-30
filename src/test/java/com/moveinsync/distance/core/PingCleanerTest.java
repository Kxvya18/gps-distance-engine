package com.moveinsync.distance.core;

import com.moveinsync.distance.model.Ping;
import com.moveinsync.distance.model.RuleConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Tests for PingCleaner.
class PingCleanerTest {

    private static final double NINE_KM_IN_DEGREES = 0.081;

    private final PingCleaner cleaner = new PingCleaner(RuleConfig.defaults());

    // A cab moving about 16 metres every 5 seconds.
    private List<Ping> normalPings(int count) {
        List<Ping> pings = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            pings.add(new Ping(12.9 + i * 0.0001, 77.6 + i * 0.0001, i * 5000L, 10));
        }
        return pings;
    }

    @Test
    void removesDuplicates() {
        List<Ping> pings = normalPings(5);
        pings.add(pings.get(2));
        assertEquals(5, cleaner.clean(pings).size());
    }

    @Test
    void sortsByTime() {
        List<Ping> pings = normalPings(5);
        java.util.Collections.reverse(pings);
        List<Ping> clean = cleaner.clean(pings);
        assertEquals(0L, clean.get(0).timestampMillis());
        assertEquals(20_000L, clean.get(4).timestampMillis());
    }

    @Test
    void dropsLowAccuracyPing() {
        List<Ping> pings = normalPings(5);
        pings.add(new Ping(12.9, 77.6, 2500L, 500));
        assertEquals(5, cleaner.clean(pings).size());
    }

    @Test
    void dropsBurstOfBadPingsFromTheMiddle() {
        List<Ping> pings = normalPings(20);
        for (int i = 0; i < 4; i++) {
            pings.add(new Ping(12.9 + NINE_KM_IN_DEGREES, 77.6, 40_000L + i * 1000 + 500, 10));
        }
        List<Ping> clean = cleaner.clean(pings);
        assertEquals(20, clean.size());
        assertTrue(clean.stream().allMatch(p -> p.latitude() < 12.91));
    }

    @Test
    void badFirstPingDoesNotLoseTheTrip() {
        List<Ping> pings = normalPings(20);
        pings.add(new Ping(12.9 + NINE_KM_IN_DEGREES, 77.6, -1000L, 10));
        assertEquals(20, cleaner.clean(pings).size());
    }

    @Test
    void emptyInputGivesEmptyOutput() {
        assertTrue(cleaner.clean(List.of()).isEmpty());
    }
}
