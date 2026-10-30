package com.moveinsync.distance.routing;

import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

// What every routing provider has in common: it is slow, rate-limited and paid per call.
// A subclass only supplies the distances, so a real maps service would plug in here.
public abstract class BaseRoutingProvider implements RoutingProvider {

    private final long latencyMillis;
    private final Semaphore rateLimit;
    private final AtomicBoolean down = new AtomicBoolean(false);
    private final AtomicLong calls = new AtomicLong();
    private volatile long extraDelayMillis = 0;

    protected BaseRoutingProvider(long latencyMillis, int maxConcurrentCalls) {
        this.latencyMillis = latencyMillis;
        this.rateLimit = new Semaphore(maxConcurrentCalls);
    }

    protected abstract long roadMeters(RouteRequest request);

    @Override
    public final List<Long> drivingMeters(List<RouteRequest> batch) {
        calls.incrementAndGet();
        if (down.get()) {
            throw new RoutingException("provider is down");
        }
        if (!rateLimit.tryAcquire()) {
            throw new RoutingException("rate limited");
        }
        try {
            pause();
            return batch.stream().map(this::roadMeters).toList();
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
