package com.moveinsync.distance.routing;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// Tests for RouteCache.
class RouteCacheTest {

    private final AtomicLong now = new AtomicLong(0);

    @Test
    void returnsWhatWasSaved() {
        RouteCache cache = new RouteCache(10, 1000, now::get);
        cache.put("a", 500);
        assertEquals(500L, cache.get("a"));
        assertEquals(1, cache.hitCount());
    }

    @Test
    void evictsTheLeastRecentlyUsedEntry() {
        RouteCache cache = new RouteCache(2, 1000, now::get);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.get("a");
        cache.put("c", 3);
        assertNull(cache.get("b"));
        assertEquals(1L, cache.get("a"));
    }

    @Test
    void expiresAfterTheTtl() {
        RouteCache cache = new RouteCache(10, 1000, now::get);
        cache.put("a", 1);
        now.set(1000);
        assertNull(cache.get("a"));
    }
}
