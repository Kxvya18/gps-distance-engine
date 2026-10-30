package com.moveinsync.distance.model;

// The stretch between two neighbouring points.
// Distance is whole metres so that adding segments never drifts.
public record Segment(TrackPoint from, TrackPoint to, long distanceMeters, SegmentRule rule) {
}