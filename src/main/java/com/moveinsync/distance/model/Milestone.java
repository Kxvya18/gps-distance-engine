package com.moveinsync.distance.model;

// A trip event: start, pickup, drop, no-show or end.
// Coordinates and time are optional, so any of them can be null.
public record Milestone(Type type, String employeeId, Double latitude, Double longitude, Long timeMillis) {

    public enum Type {
        TRIP_START, PICKUP, DROP, NO_SHOW, TRIP_END
    }
}
