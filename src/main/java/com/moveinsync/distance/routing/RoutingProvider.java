package com.moveinsync.distance.routing;

import java.util.List;

// A maps service. One call can carry a batch of requests, and answers come back in the same order.
public interface RoutingProvider {
    List<Long> drivingMeters(List<RouteRequest> batch);
}
