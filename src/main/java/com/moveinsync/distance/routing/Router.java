package com.moveinsync.distance.routing;

import java.util.List;

// What the billing engine needs from routing: answers for a list of gaps.
public interface Router {
    RouteBatch routeAll(List<RouteRequest> requests);
}
