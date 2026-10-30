package com.moveinsync.distance.error;

// Also used for another tenant's trip, so a caller cannot learn that the trip exists.
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
