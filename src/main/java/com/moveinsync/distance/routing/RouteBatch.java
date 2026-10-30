package com.moveinsync.distance.routing;

import java.util.List;

// Answers in the same order as the requests. A null answer means no route was available.
public record RouteBatch(List<Long> meters, int providerCalls) {
}
