package com.moveinsync.distance.routing;

import java.util.function.LongSupplier;

// Stops calling the provider while it is failing.
// CLOSED: calls go through. OPEN: calls are refused until the cool-down ends.
// HALF_OPEN: one test call decides whether to close again.
public class CircuitBreaker {

    public enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    private final int failureThreshold;
    private final long coolDownMillis;
    private final LongSupplier clock;

    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private long openedAtMillis = 0;
    private boolean testCallInFlight = false;

    public CircuitBreaker(int failureThreshold, long coolDownMillis, LongSupplier clock) {
        this.failureThreshold = failureThreshold;
        this.coolDownMillis = coolDownMillis;
        this.clock = clock;
    }

    public synchronized boolean allowRequest() {
        if (state == State.OPEN && clock.getAsLong() - openedAtMillis >= coolDownMillis) {
            state = State.HALF_OPEN;
            testCallInFlight = false;
        }
        if (state == State.OPEN) {
            return false;
        }
        if (state == State.HALF_OPEN) {
            if (testCallInFlight) {
                return false;
            }
            testCallInFlight = true;
        }
        return true;
    }

    public synchronized void recordSuccess() {
        state = State.CLOSED;
        consecutiveFailures = 0;
        testCallInFlight = false;
    }

    public synchronized void recordFailure() {
        consecutiveFailures++;
        if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
            state = State.OPEN;
            openedAtMillis = clock.getAsLong();
            testCallInFlight = false;
        }
    }

    public synchronized State state() {
        return state;
    }
}
