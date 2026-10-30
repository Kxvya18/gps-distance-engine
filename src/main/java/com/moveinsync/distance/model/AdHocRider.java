package com.moveinsync.distance.model;

// A rider not on the plan: their profile home location and when they signed in.
public record AdHocRider(String employeeId, double homeLatitude, double homeLongitude, long signInTimeMillis) {
}
