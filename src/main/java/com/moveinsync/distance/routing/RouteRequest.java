package com.moveinsync.distance.routing;

// Asks for the road distance between two points.
public record RouteRequest(double fromLatitude, double fromLongitude, double toLatitude, double toLongitude) {
}
