package com.moveinsync.distance.service;

import com.moveinsync.distance.entity.TripRecord;
import com.moveinsync.distance.model.TripResult;
import com.moveinsync.distance.repository.TripRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

// Recomputes DEGRADED trips automatically once the provider is back.
@Service
public class RecomputeService {

    private static final Logger log = LoggerFactory.getLogger(RecomputeService.class);
    private static final int BATCH = 100;

    private final TripRecordRepository trips;
    private final TripService tripService;

    public RecomputeService(TripRecordRepository trips, TripService tripService) {
        this.trips = trips;
        this.tripService = tripService;
    }

    @Scheduled(fixedDelayString = "${app.recompute-delay-millis}")
    public int recomputeDegradedTrips() {
        List<TripRecord> degraded = trips.findByStatus("DEGRADED", PageRequest.of(0, BATCH));
        int fixed = 0;
        for (TripRecord record : degraded) {
            TripResult result;
            try {
                result = tripService.recompute(record);
            } catch (ConcurrencyFailureException e) {
                continue;
            }
            // Still degraded means the provider is still down, so stop and try again later.
            if (result.status() == TripResult.Status.DEGRADED) {
                break;
            }
            fixed++;
        }
        if (fixed > 0) {
            log.info("recomputed degraded trips count={}", fixed);
        }
        return fixed;
    }
}
