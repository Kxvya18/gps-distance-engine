package com.moveinsync.distance.dto;

import com.moveinsync.distance.model.Segment;
import com.moveinsync.distance.model.SegmentRule;
import com.moveinsync.distance.model.TripResult;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

// JSON returned by the API. Employees get the labels but not the coordinates,
// because a segment's end point can be someone's home.
public record TripResponse(
        String tripId,
        String status,
        int ruleVersion,
        long totalMeters,
        double billableKm,
        int providerCalls,
        Map<SegmentRule, Long> metersByRule,
        List<SegmentLine> segments) {

    public record SegmentLine(long fromTimeMillis, long toTimeMillis, long distanceMeters, SegmentRule rule,
                              Double fromLatitude, Double fromLongitude, Double toLatitude, Double toLongitude) {
    }

    public static TripResponse from(TripResult result, boolean withCoordinates) {
        Map<SegmentRule, Long> byRule = new EnumMap<>(SegmentRule.class);
        List<SegmentLine> lines = new ArrayList<>();
        for (Segment s : result.segments()) {
            byRule.merge(s.rule(), s.distanceMeters(), Long::sum);
            lines.add(new SegmentLine(s.from().timeMillis(), s.to().timeMillis(), s.distanceMeters(), s.rule(),
                    withCoordinates ? s.from().latitude() : null,
                    withCoordinates ? s.from().longitude() : null,
                    withCoordinates ? s.to().latitude() : null,
                    withCoordinates ? s.to().longitude() : null));
        }
        return new TripResponse(result.tripId(), result.status().name(), result.ruleVersion(), result.totalMeters(),
                result.billableKm(), result.providerCalls(), byRule, lines);
    }
}
