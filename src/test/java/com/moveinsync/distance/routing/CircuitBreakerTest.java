package com.moveinsync.distance.routing;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircuitBreakerTest {

    private final AtomicLong now = new AtomicLong(0);
    private final CircuitBreaker breaker = new CircuitBreaker(3, 1000, now::get);

    private void failThreeTimes() {
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();
    }

    @Test
    void opensAfterTheThreshold() {
        failThreeTimes();
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.allowRequest());
    }

    @Test
    void aSuccessResetsTheFailureCount() {
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess();
        breaker.recordFailure();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void allowsOnlyOneTestCallAfterTheCoolDown() {
        failThreeTimes();
        now.set(1000);
        assertTrue(breaker.allowRequest());
        assertFalse(breaker.allowRequest());
    }

    @Test
    void successfulTestCallClosesTheBreaker() {
        failThreeTimes();
        now.set(1000);
        breaker.allowRequest();
        breaker.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void failedTestCallReopensTheBreaker() {
        failThreeTimes();
        now.set(1000);
        breaker.allowRequest();
        breaker.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.allowRequest());
    }
}
