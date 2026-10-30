package com.moveinsync.distance.routing;

import com.moveinsync.distance.core.GeoMath;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

// Stands in for a paid maps service so tests are free and failures can be switched on.
// The fake city is a street grid, so the road distance is north-south plus east-west.
public class FakeRoutingProvider implements RoutingProvider {

    private final long latencyMillis;
    private final Semaphore rateLimit;
    private final AtomicBoolean down = new AtomicBoolean(false);
    private final AtomicLong calls = new AtomicLong();
    private volatile long extraDelayMillis = 0;

    public FakeRoutingProvider(long latencyMillis, int maxConcurrentCalls) {
        this.latencyMillis = latencyMillis;
        this.rateLimit = new Semaphore(maxConcurrentCalls);
    }

    @Override
    public List<Long> drivingMeters(List<RouteRequest> batch) {
        calls.incrementAndGet();
        if (down.get()) {
            throw new RoutingException("provider is down");
        }
        if (!rateLimit.tryAcquire()) {
            throw new RoutingException("rate limited");
        }
        try {
            pause();
            List<Long> answers = new ArrayList<>();
            for (RouteRequest request : batch) {
                answers.add(gridMeters(request));
            }
            return answers;
        } finally {
            rateLimit.release();
        }
    }

    private void pause() {
        long delay = latencyMillis + extraDelayMillis;
        if (delay == 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RoutingException("interrupted");
        }
    }

    private long gridMeters(RouteRequest r) {
        double northSouth = GeoMath.metersBetween(r.fromLatitude(), r.fromLongitude(), r.toLatitude(), r.fromLongitude());
        double eastWest = GeoMath.metersBetween(r.toLatitude(), r.fromLongitude(), r.toLatitude(), r.toLongitude());
        return Math.round(northSouth + eastWest);
    }

    // Makes the provider slow enough to hang, for timeout tests.
    public void setExtraDelayMillis(long millis) {
        extraDelayMillis = millis;
    }

    public void setDown(boolean isDown) {
        down.set(isDown);
    }

    public boolean isDown() {
        return down.get();
    }

    public long callCount() {
        return calls.get();
    }
}
