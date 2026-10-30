package com.moveinsync.distance.error;

import org.springframework.http.HttpStatus;

// The request itself is wrong. The message must not repeat any of the request's data.
public class BadRequestException extends ApiException {

    public BadRequestException(String message) {
        super(message);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.BAD_REQUEST;
    }
}
