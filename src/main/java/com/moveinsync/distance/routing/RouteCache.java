package com.moveinsync.distance.routing;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

// Saves route answers by snapped start and end cell.
// Eviction is least-recently-used, and an entry also expires after a fixed time.
public class RouteCache {

    private record Entry(long meters, long savedAtMillis) {
    }

    private final int maxEntries;
    private final long ttlMillis;
    private final LongSupplier clock;
    private final Map<String, Entry> entries;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public RouteCache(int maxEntries, long ttlMillis, LongSupplier clock) {
        this.maxEntries = maxEntries;
        this.ttlMillis = ttlMillis;
        this.clock = clock;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > RouteCache.this.maxEntries;
            }
        };
    }

    public synchronized Long get(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            misses.incrementAndGet();
            return null;
        }
        if (clock.getAsLong() - entry.savedAtMillis() >= ttlMillis) {
            entries.remove(key);
            misses.incrementAndGet();
            return null;
        }
        hits.incrementAndGet();
        return entry.meters();
    }

    public synchronized void put(String key, long meters) {
        entries.put(key, new Entry(meters, clock.getAsLong()));
    }

    public synchronized int size() {
        return entries.size();
    }

    public long hitCount() {
        return hits.get();
    }

    public long missCount() {
        return misses.get();
    }
}
