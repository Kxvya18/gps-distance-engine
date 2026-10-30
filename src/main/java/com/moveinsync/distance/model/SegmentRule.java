package com.moveinsync.distance.model;

// The reason a segment got its distance. This is what answers a vendor dispute.
public enum SegmentRule {
    SHORT_GAP_AERIAL,
    PARKED,
    ROUTED,
    REJECTED_DISTANCE,
    REJECTED_SPEED,
    PROVIDER_DOWN_ESTIMATE
}