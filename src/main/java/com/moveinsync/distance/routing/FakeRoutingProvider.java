package com.moveinsync.distance.routing;

import com.moveinsync.distance.core.GeoMath;

// Stands in for a paid maps service so tests are free and failures can be switched on.
// The fake city is a street grid, so the road distance is north-south plus east-west.
public class FakeRoutingProvider extends BaseRoutingProvider {

    public FakeRoutingProvider(long latencyMillis, int maxConcurrentCalls) {
        super(latencyMillis, maxConcurrentCalls);
    }

    @Override
    protected long roadMeters(RouteRequest r) {
        double northSouth = GeoMath.metersBetween(r.fromLatitude(), r.fromLongitude(), r.toLatitude(), r.fromLongitude());
        double eastWest = GeoMath.metersBetween(r.toLatitude(), r.fromLongitude(), r.toLatitude(), r.toLongitude());
        return Math.round(northSouth + eastWest);
    }
}
