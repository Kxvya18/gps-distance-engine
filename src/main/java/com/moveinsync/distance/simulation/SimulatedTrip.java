package com.moveinsync.distance.simulation;

import com.moveinsync.distance.model.TripEvidence;

// A fake trip plus the distance the cab really drove.
public record SimulatedTrip(TripEvidence evidence, long trueMeters) {
}
