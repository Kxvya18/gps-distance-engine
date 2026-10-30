package com.moveinsync.distance.error;

import org.springframework.http.HttpStatus;

// Also used for another tenant's trip, so a caller cannot learn that the trip exists.
public class NotFoundException extends ApiException {

    public NotFoundException(String message) {
        super(message);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }
}
