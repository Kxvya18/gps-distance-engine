package com.moveinsync.distance.model;

import java.util.List;

// The final answer for one trip.
public record TripResult(
        String tripId,
        int ruleVersion,
        long totalMeters,
        List<Segment> segments,
        Status status,
        int providerCalls) {

    public enum Status {
        OK, DEGRADED, NEEDS_REVIEW
    }

    // Rounds once, here, to one decimal place of a kilometre. 50 metres rounds up.
    public double billableKm() {
        long tenthsOfKm = (totalMeters + 50) / 100;
        return tenthsOfKm / 10.0;
    }
}