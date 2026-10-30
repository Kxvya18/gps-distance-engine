package com.moveinsync.distance.error;

// The request is valid but the trip is not in the right state for it.
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
