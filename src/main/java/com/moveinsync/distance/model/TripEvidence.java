package com.moveinsync.distance.model;

import java.util.List;

// Everything we receive about one trip.
// LOGIN trips end at the office and LOGOUT trips start there.
public record TripEvidence(
        String tripId,
        String tenantId,
        Direction direction,
        List<Ping> pings,
        List<Milestone> milestones,
        List<PlannedStop> plannedStops,
        List<AdHocRider> adHocRiders,
        double officeLatitude,
        double officeLongitude) {

    public enum Direction {
        LOGIN, LOGOUT
    }
}
