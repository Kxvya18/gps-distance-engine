package com.moveinsync.distance.error;

import org.springframework.dao.ConcurrencyFailureException;

// The stored evidence changed after this result was computed, so the result must not be saved.
public class StaleUpdateException extends ConcurrencyFailureException {
    public StaleUpdateException(String message) {
        super(message);
    }
}
