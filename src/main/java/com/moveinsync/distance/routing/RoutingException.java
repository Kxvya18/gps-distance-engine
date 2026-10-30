package com.moveinsync.distance.routing;

// The provider failed, timed out or refused the call.
public class RoutingException extends RuntimeException {
    public RoutingException(String message) {
        super(message);
    }
}
