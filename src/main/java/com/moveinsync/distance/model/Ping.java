package com.moveinsync.distance.model;

// One raw GPS fix from the cab's device.
public record Ping(double latitude, double longitude, long timestampMillis, double accuracyMeters) {
}