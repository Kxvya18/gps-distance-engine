package com.moveinsync.distance.model;

// One raw GPS dot: latitude, longitude, time, accuracy.

// One raw GPS dot from the cab's device.
public record Ping(double latitude, double longitude, long timestampMillis, double accuracyMeters) {
}