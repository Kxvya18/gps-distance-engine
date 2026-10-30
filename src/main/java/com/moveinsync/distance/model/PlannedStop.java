package com.moveinsync.distance.model;

// Where and when a planned rider was due to be picked up or dropped.
public record PlannedStop(String employeeId, double latitude, double longitude, long timeMillis) {
}
