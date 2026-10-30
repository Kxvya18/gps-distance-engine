package com.moveinsync.distance.error;

import org.springframework.http.HttpStatus;

// An error that is safe to show the caller. Each subclass says which HTTP status it means.
public abstract class ApiException extends RuntimeException {

    protected ApiException(String message) {
        super(message);
    }

    public abstract HttpStatus status();
}
