package com.moveinsync.distance.simulation;

import com.moveinsync.distance.model.Milestone;
import com.moveinsync.distance.model.Ping;
import com.moveinsync.distance.model.TripEvidence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

// Generates fake trips with a known true distance.
// The city is a street grid. A fixed set of corridors means different trips repeat
// the same roads, tunnels and stops, as real shift routes do.
public class TripSimulator {

    private static final double OFFICE_LAT = 12.97;
    private static final double OFFICE_LON = 77.59;
    private static final double METERS_PER_DEGREE = 111_195.0;
    private static final int CORRIDORS = 300;
    private static final int PING_SECONDS = 3;
    private static final int STOP_SECONDS = 45;
    private static final int BASEMENT_SECONDS = 600;
    private static final long DAY_START_MILLIS = 1_790_000_000_000L;

    private final long seed;

    public TripSimulator(long seed) {
        this.seed = seed;
    }

    // One corridor: a staircase route out from the office, with its stops and tunnels.
    private record Corridor(List<double[]> corners, double totalKm, List<Double> stopKms, List<double[]> tunnels) {
    }

    public SimulatedTrip generate(int index) {
        Random random = new Random(seed * 1_000_003L + index);
        Corridor corridor = corridor(index % CORRIDORS);
        boolean logout = random.nextBoolean();
        boolean basementStop = random.nextDouble() < 0.10;

        List<Ping> pings = new ArrayList<>();
        List<Milestone> milestones = new ArrayList<>();
        long startMillis = DAY_START_MILLIS + index * 60_000L;
        long time = startMillis;

        double[] start = position(corridor, logout ? 0 : corridor.totalKm());
        milestones.add(point(Milestone.Type.TRIP_START, "cab", start, time));

        // Stops in the order this trip meets them.
        List<Double> stops = new ArrayList<>(corridor.stopKms());
        if (!logout) {
            Collections.reverse(stops);
        }

        double travelledKm = 0;
        double driftNorth = 0;
        double driftEast = 0;
        int nextStop = 0;
        double speedKmh = 18 + random.nextDouble() * 17;

        while (travelledKm < corridor.totalKm()) {
            double km = logout ? travelledKm : corridor.totalKm() - travelledKm;

            // GPS error drifts slowly, so neighbouring pings are wrong in nearly the same way.
            driftNorth = 0.95 * driftNorth + random.nextGaussian() * 1.25;
            driftEast = 0.95 * driftEast + random.nextGaussian() * 1.25;

            if (!inTunnel(corridor, km)) {
                addPing(pings, random, position(corridor, km), driftNorth, driftEast, time);
            }

            if (nextStop < stops.size() && reachedStop(stops.get(nextStop), km, logout)) {
                double[] where = position(corridor, stops.get(nextStop));
                milestones.add(employeeMilestone(logout, nextStop, where, time, random));
                nextStop++;
                boolean basement = basementStop && nextStop == 2;
                time = stopFor(pings, random, where, time, basement);
            }

            if (random.nextDouble() < 0.02) {
                speedKmh = 18 + random.nextDouble() * 17;
            }
            travelledKm += speedKmh * PING_SECONDS / 3600.0;
            time += PING_SECONDS * 1000L;
        }

        double[] end = position(corridor, logout ? corridor.totalKm() : 0);
        addRemainingStops(milestones, stops, nextStop, corridor, logout, time, random);
        milestones.add(tripEnd(end, time, logout, random));

        addBadData(pings, random);

        TripEvidence evidence = new TripEvidence("trip-" + index, "client-" + (index % 5),
                logout ? TripEvidence.Direction.LOGOUT : TripEvidence.Direction.LOGIN,
                pings, milestones, OFFICE_LAT, OFFICE_LON);
        return new SimulatedTrip(evidence, Math.round(corridor.totalKm() * 1000));
    }

    private boolean reachedStop(double stopKm, double km, boolean logout) {
        return logout ? km >= stopKm : km <= stopKm;
    }

    private Milestone employeeMilestone(boolean logout, int stopNumber, double[] where, long time, Random random) {
        Milestone.Type type = logout ? Milestone.Type.DROP : Milestone.Type.PICKUP;
        String employee = "emp-" + stopNumber;
        double plannedLat = where[0] + 0.0002;
        double plannedLon = where[1] + 0.0002;
        long plannedTime = time + 30_000;
        if (random.nextDouble() < 0.05) {
            return new Milestone(type, employee, null, null, null, plannedLat, plannedLon, plannedTime);
        }
        return new Milestone(type, employee, where[0], where[1], time, plannedLat, plannedLon, plannedTime);
    }

    private void addRemainingStops(List<Milestone> milestones, List<Double> stops, int nextStop, Corridor corridor,
                                   boolean logout, long time, Random random) {
        for (int i = nextStop; i < stops.size(); i++) {
            milestones.add(employeeMilestone(logout, i, position(corridor, stops.get(i)), time, random));
        }
    }

    // Parked cab: pings keep arriving, unless it is a basement where GPS is lost.
    private long stopFor(List<Ping> pings, Random random, double[] where, long time, boolean basement) {
        if (basement) {
            return time + BASEMENT_SECONDS * 1000L;
        }
        for (int i = 0; i < STOP_SECONDS / PING_SECONDS; i++) {
            time += PING_SECONDS * 1000L;
            addPing(pings, random, where, 0, 0, time);
        }
        return time;
    }

    // A few trips report a trip end far from the office, which the 400 m rule must catch.
    private Milestone tripEnd(double[] end, long time, boolean logout, Random random) {
        if (!logout && random.nextDouble() < 0.10) {
            return point(Milestone.Type.TRIP_END, "cab", new double[]{end[0] + 0.008, end[1]}, time);
        }
        return point(Milestone.Type.TRIP_END, "cab", end, time);
    }

    private Milestone point(Milestone.Type type, String who, double[] where, long time) {
        return new Milestone(type, who, where[0], where[1], time, null, null, null);
    }

    private void addPing(List<Ping> pings, Random random, double[] where, double driftNorth, double driftEast, long time) {
        boolean weakFix = random.nextDouble() < 0.02;
        double extra = weakFix ? 60 : 0;
        double accuracy = weakFix ? 80 : 5 + random.nextDouble() * 10;
        double lat = where[0] + (driftNorth + extra) / METERS_PER_DEGREE;
        double lon = where[1] + (driftEast + extra) / METERS_PER_DEGREE;
        pings.add(new Ping(lat, lon, time, accuracy));
    }

    // Duplicates, out-of-order delivery and bursts of false fixes.
    private void addBadData(List<Ping> pings, Random random) {
        int count = pings.size();
        for (int i = 0; i < count / 100; i++) {
            pings.add(pings.get(random.nextInt(count)));
        }
        for (int i = 0; i < pings.size() / 30; i++) {
            int position = random.nextInt(pings.size() - 1);
            Collections.swap(pings, position, position + 1);
        }
        if (count > 10 && random.nextDouble() < 0.05) {
            Ping near = pings.get(random.nextInt(count));
            for (int i = 1; i <= 3; i++) {
                pings.add(new Ping(near.latitude() + 0.081, near.longitude(), near.timestampMillis() + i * 500L, 10));
            }
        }
    }

    private boolean inTunnel(Corridor corridor, double km) {
        for (double[] tunnel : corridor.tunnels()) {
            if (km >= tunnel[0] && km <= tunnel[1]) {
                return true;
            }
        }
        return false;
    }

    private Corridor corridor(int number) {
        Random random = new Random(seed + number);
        double targetKm = 20 + random.nextDouble() * 20;
        double northSign = random.nextBoolean() ? 1 : -1;
        double eastSign = random.nextBoolean() ? 1 : -1;

        List<double[]> corners = new ArrayList<>();
        corners.add(new double[]{OFFICE_LAT, OFFICE_LON});
        double lat = OFFICE_LAT;
        double lon = OFFICE_LON;
        double totalKm = 0;
        boolean goNorth = random.nextBoolean();
        while (totalKm < targetKm) {
            double legKm = 0.3 + random.nextDouble() * 1.2;
            double degrees = legKm * 1000 / METERS_PER_DEGREE;
            if (goNorth) {
                lat += northSign * degrees;
            } else {
                lon += eastSign * degrees;
            }
            corners.add(new double[]{lat, lon});
            totalKm += legKm;
            goNorth = !goNorth;
        }

        List<Double> stops = new ArrayList<>();
        int stopCount = 3 + random.nextInt(2);
        for (int i = 1; i <= stopCount; i++) {
            stops.add(totalKm * (0.4 + 0.6 * i / stopCount));
        }

        List<double[]> tunnels = new ArrayList<>();
        int tunnelCount = 1 + random.nextInt(2);
        for (int i = 0; i < tunnelCount; i++) {
            double from = totalKm * (0.15 + 0.35 * i) + random.nextDouble();
            tunnels.add(new double[]{from, from + 2 + random.nextDouble() * 7});
        }
        return new Corridor(corners, totalKm, stops, tunnels);
    }

    private double[] position(Corridor corridor, double km) {
        List<double[]> corners = corridor.corners();
        double remaining = Math.max(0, km);
        for (int i = 0; i + 1 < corners.size(); i++) {
            double[] a = corners.get(i);
            double[] b = corners.get(i + 1);
            double legKm = (Math.abs(b[0] - a[0]) + Math.abs(b[1] - a[1])) * METERS_PER_DEGREE / 1000;
            if (remaining <= legKm) {
                double share = remaining / legKm;
                return new double[]{a[0] + (b[0] - a[0]) * share, a[1] + (b[1] - a[1]) * share};
            }
            remaining -= legKm;
        }
        return corners.get(corners.size() - 1);
    }
}
