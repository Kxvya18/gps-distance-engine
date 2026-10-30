package com.moveinsync.distance.model;

// A point we measure from: either a clean ping or an anchor.
public record TrackPoint(double latitude, double longitude, long timeMillis, Source source) {

    public enum Source {
        PING, EMPLOYEE_ANCHOR, NO_SHOW_ANCHOR, OFFICE_ANCHOR
    }
}