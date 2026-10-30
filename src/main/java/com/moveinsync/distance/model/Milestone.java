package com.moveinsync.distance.model;

// A trip event: start, pickup, drop, no-show or end.
// Location and time can be null when the device did not record them.
// The fallback values are the profile home and sign-in time for an ad-hoc rider,
// or the planned location and time for a planned rider.
public record Milestone(
        Type type,
        String employeeId,
        Double latitude,
        Double longitude,
        Long timeMillis,
        Double fallbackLatitude,
        Double fallbackLongitude,
        Long fallbackTimeMillis) {

    public enum Type {
        TRIP_START, PICKUP, DROP, NO_SHOW, TRIP_END
    }
}