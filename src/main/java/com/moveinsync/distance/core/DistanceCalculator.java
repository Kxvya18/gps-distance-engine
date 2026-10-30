package com.moveinsync.distance.core;

import com.moveinsync.distance.model.Ping;
import com.moveinsync.distance.model.RuleConfig;
import com.moveinsync.distance.model.Segment;
import com.moveinsync.distance.model.SegmentRule;
import com.moveinsync.distance.model.TrackPoint;
import com.moveinsync.distance.model.TripEvidence;
import com.moveinsync.distance.model.TripResult;
import com.moveinsync.distance.routing.RouteBatch;
import com.moveinsync.distance.routing.RouteRequest;
import com.moveinsync.distance.routing.Router;

import java.util.ArrayList;
import java.util.List;

// Runs the whole pipeline for one trip: clean, add anchors, measure, add up.
public class DistanceCalculator {

    private final RuleConfig config;
    private final PingCleaner cleaner;
    private final AnchorBuilder anchorBuilder;
    private final SegmentRuleEngine rules;
    private final Router router;

    public DistanceCalculator(RuleConfig config, Router router) {
        this.config = config;
        this.cleaner = new PingCleaner(config);
        this.anchorBuilder = new AnchorBuilder(config);
        this.rules = new SegmentRuleEngine(config);
        this.router = router;
    }

    public TripResult calculate(TripEvidence evidence) {
        List<Ping> cleanPings = cleaner.clean(evidence.pings());
        List<TrackPoint> points = anchorBuilder.build(evidence, cleanPings);

        // All gaps of a trip go to the router together so they can be batched.
        List<RouteRequest> gaps = new ArrayList<>();
        for (int i = 0; i + 1 < points.size(); i++) {
            TrackPoint from = points.get(i);
            TrackPoint to = points.get(i + 1);
            if (rules.needsRoute(from, to)) {
                gaps.add(new RouteRequest(from.latitude(), from.longitude(), to.latitude(), to.longitude()));
            }
        }
        RouteBatch answers = gaps.isEmpty() ? new RouteBatch(List.of(), 0) : router.routeAll(gaps);

        List<Segment> segments = new ArrayList<>();
        int nextAnswer = 0;
        for (int i = 0; i + 1 < points.size(); i++) {
            TrackPoint from = points.get(i);
            TrackPoint to = points.get(i + 1);
            if (rules.needsRoute(from, to)) {
                segments.add(rules.routedSegment(from, to, answers.meters().get(nextAnswer)));
                nextAnswer++;
            } else {
                segments.add(rules.localSegment(from, to));
            }
        }

        long totalMeters = 0;
        for (Segment segment : segments) {
            totalMeters += segment.distanceMeters();
        }
        return new TripResult(evidence.tripId(), config.ruleVersion(), totalMeters, segments,
                statusOf(points, segments), answers.providerCalls());
    }

    private TripResult.Status statusOf(List<TrackPoint> points, List<Segment> segments) {
        if (points.size() < 2) {
            return TripResult.Status.NEEDS_REVIEW;
        }
        boolean rejected = false;
        for (Segment segment : segments) {
            if (segment.rule() == SegmentRule.PROVIDER_DOWN_ESTIMATE) {
                return TripResult.Status.DEGRADED;
            }
            if (segment.rule() == SegmentRule.REJECTED_DISTANCE || segment.rule() == SegmentRule.REJECTED_SPEED) {
                rejected = true;
            }
        }
        return rejected ? TripResult.Status.NEEDS_REVIEW : TripResult.Status.OK;
    }
}
