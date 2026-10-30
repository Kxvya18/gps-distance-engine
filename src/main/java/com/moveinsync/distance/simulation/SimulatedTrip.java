package com.moveinsync.distance.simulation;

import com.moveinsync.distance.model.TripEvidence;

// A fake trip, the distance the cab really drove, and how much of that had no GPS.
public record SimulatedTrip(TripEvidence evidence, long trueMeters, long gapMeters) {
}
