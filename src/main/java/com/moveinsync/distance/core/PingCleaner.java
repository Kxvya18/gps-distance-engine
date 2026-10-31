package com.moveinsync.distance.core;

import com.moveinsync.distance.model.Ping;
import com.moveinsync.distance.model.RuleConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// Removes duplicates, sorts by time, drops low-accuracy and teleport pings.
public class PingCleaner {

    private final RuleConfig config;

    public PingCleaner(RuleConfig config) {
        this.config = config;
    }

    public List<Ping> clean(List<Ping> rawPings) {
        List<Ping> sorted = sortAndRemoveDuplicates(rawPings);
        List<Ping> accurate = dropLowAccuracy(sorted);
        return dropTeleports(accurate);
    }

    private List<Ping> sortAndRemoveDuplicates(List<Ping> pings) {
        List<Ping> sorted = new ArrayList<>(pings);
        sorted.sort(Comparator.comparingLong(Ping::timestampMillis)
                .thenComparingDouble(Ping::latitude)
                .thenComparingDouble(Ping::longitude)
                .thenComparingDouble(Ping::accuracyMeters));

        List<Ping> unique = new ArrayList<>();
        for (Ping ping : sorted) {
            if (unique.isEmpty() || !isSameFix(unique.get(unique.size() - 1), ping)) {
                unique.add(ping);
            }
        }
        return unique;
    }

    private boolean isSameFix(Ping a, Ping b) {
        return a.timestampMillis() == b.timestampMillis()
                && a.latitude() == b.latitude()
                && a.longitude() == b.longitude();
    }

    private List<Ping> dropLowAccuracy(List<Ping> pings) {
        List<Ping> accurate = new ArrayList<>();
        for (Ping ping : pings) {
            if (ping.accuracyMeters() <= config.maxAccuracyMeters()) {
                accurate.add(ping);
            }
        }
        return accurate;
    }

    // Comparing each ping to the last good one fails if the first ping is bad, because
    // every good ping then looks like a teleport. Keeping the longest chain of plausible
    // pings avoids that.
    private List<Ping> dropTeleports(List<Ping> pings) {
        List<List<Ping>> chains = new ArrayList<>();
        for (Ping ping : pings) {
            List<Ping> chain = newestChainThatFits(chains, ping);
            if (chain == null) {
                chain = new ArrayList<>();
                chains.add(chain);
            }
            chain.add(ping);
        }
        return longest(chains);
    }

    private List<Ping> newestChainThatFits(List<List<Ping>> chains, Ping ping) {
        List<Ping> best = null;
        long bestTime = Long.MIN_VALUE;
        for (List<Ping> chain : chains) {
            Ping last = chain.get(chain.size() - 1);
            if (isPlausible(last, ping) && last.timestampMillis() > bestTime) {
                best = chain;
                bestTime = last.timestampMillis();
            }
        }
        return best;
    }

    private boolean isPlausible(Ping from, Ping to) {
        double meters = GeoMath.metersBetween(from.latitude(), from.longitude(), to.latitude(), to.longitude());
        long millis = to.timestampMillis() - from.timestampMillis();
        return GeoMath.speedKmh(meters, millis) <= config.maxSpeedKmh();
    }

    private List<Ping> longest(List<List<Ping>> chains) {
        List<Ping> best = new ArrayList<>();
        for (List<Ping> chain : chains) {
            if (chain.size() > best.size()) {
                best = chain;
            }
        }
        return best;
    }
}
