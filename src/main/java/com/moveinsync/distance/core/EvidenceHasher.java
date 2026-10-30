package com.moveinsync.distance.core;

import com.moveinsync.distance.model.AdHocRider;
import com.moveinsync.distance.model.Milestone;
import com.moveinsync.distance.model.Ping;
import com.moveinsync.distance.model.PlannedStop;
import com.moveinsync.distance.model.TripEvidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;

// A fingerprint of the input. The same evidence in any order, with any duplicates,
// gives the same hash, so a replayed feed can be recognised and ignored.
public final class EvidenceHasher {

    private EvidenceHasher() {
    }

    public static String hash(TripEvidence evidence, int ruleVersion) {
        TreeSet<String> pings = new TreeSet<>();
        for (Ping ping : evidence.pings()) {
            pings.add(ping.toString());
        }
        TreeSet<String> milestones = new TreeSet<>();
        for (Milestone milestone : evidence.milestones()) {
            milestones.add(milestone.toString());
        }

        TreeSet<String> riders = new TreeSet<>();
        for (PlannedStop stop : evidence.plannedStops()) {
            riders.add(stop.toString());
        }
        for (AdHocRider rider : evidence.adHocRiders()) {
            riders.add(rider.toString());
        }

        List<String> parts = new ArrayList<>();
        parts.add("rules=" + ruleVersion);
        parts.add(String.valueOf(evidence.direction()));
        parts.add(evidence.officeLatitude() + "," + evidence.officeLongitude());
        parts.addAll(pings);
        parts.addAll(milestones);
        parts.addAll(riders);

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(String.join("\n", parts).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing", e);
        }
    }
}
