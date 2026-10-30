package com.moveinsync.distance.error;

import org.springframework.http.HttpStatus;

// The request is valid but the trip is not in the right state for it.
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(message);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
