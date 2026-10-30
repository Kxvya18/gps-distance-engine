package com.moveinsync.distance.service;

import com.moveinsync.distance.audit.TripAuditor;
import com.moveinsync.distance.core.DistanceCalculator;
import com.moveinsync.distance.core.EvidenceHasher;
import com.moveinsync.distance.dto.InvoiceResponse;
import com.moveinsync.distance.entity.Invoice;
import com.moveinsync.distance.entity.TripRecord;
import com.moveinsync.distance.error.BadRequestException;
import com.moveinsync.distance.error.ConflictException;
import com.moveinsync.distance.error.NotFoundException;
import com.moveinsync.distance.model.Milestone;
import com.moveinsync.distance.model.Ping;
import com.moveinsync.distance.model.RuleConfig;
import com.moveinsync.distance.model.Segment;
import com.moveinsync.distance.model.SegmentRule;
import com.moveinsync.distance.model.TripEvidence;
import com.moveinsync.distance.model.TripResult;
import com.moveinsync.distance.monitoring.TripMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

// Computes a trip and saves the result. Logs carry trip ids only, never locations.
@Service
public class TripService {

    private static final Logger log = LoggerFactory.getLogger(TripService.class);
    private static final int MAX_UPDATE_ATTEMPTS = 10;
    private static final int RETRY_PAUSE_MILLIS = 20;

    private final DistanceCalculator calculator;
    private final TripAuditor auditor;
    private final RuleConfig config;
    private final TripStore store;
    private final ObjectMapper mapper;
    private final TripMetrics metrics;

    public TripService(DistanceCalculator calculator, TripAuditor auditor, RuleConfig config, TripStore store,
                       ObjectMapper mapper, TripMetrics metrics) {
        this.calculator = calculator;
        this.auditor = auditor;
        this.config = config;
        this.store = store;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    public TripResult submit(TripEvidence evidence) {
        requireValidPings(evidence.pings());
        String hash = EvidenceHasher.hash(evidence, config.ruleVersion());

        for (int attempt = 1; attempt <= MAX_UPDATE_ATTEMPTS; attempt++) {
            // Replaying the same evidence changes nothing and costs no provider calls.
            Optional<TripRecord> existing = store.find(evidence.tenantId(), evidence.tripId());
            if (existing.isPresent() && hash.equals(existing.get().getEvidenceHash())
                    && !"DEGRADED".equals(existing.get().getStatus())) {
                return readResult(existing.get());
            }
            try {
                return computeAndSave(evidence, hash, existing.map(TripRecord::getEvidenceHash).orElse(null));
            } catch (ConcurrencyFailureException | DataIntegrityViolationException e) {
                metrics.concurrentUpdateRetried();
                pauseBeforeRetry(attempt);
            }
        }
        throw new ConflictException("Trip is being updated too often, try again");
    }

    public TripResult addLatePings(String tenant, String tripId, List<Ping> latePings) {
        requireValidPings(latePings);
        for (int attempt = 1; attempt <= MAX_UPDATE_ATTEMPTS; attempt++) {
            TripRecord record = load(tenant, tripId);
            TripEvidence old = readEvidence(record);
            List<Ping> merged = new ArrayList<>(old.pings());
            merged.addAll(latePings);
            TripEvidence updated = new TripEvidence(old.tripId(), old.tenantId(), old.direction(), merged,
                    old.milestones(), old.plannedStops(), old.adHocRiders(), old.officeLatitude(), old.officeLongitude());
            String hash = EvidenceHasher.hash(updated, config.ruleVersion());
            if (hash.equals(record.getEvidenceHash())) {
                return readResult(record);
            }
            try {
                return computeAndSave(updated, hash, record.getEvidenceHash());
            } catch (ConcurrencyFailureException | DataIntegrityViolationException e) {
                metrics.concurrentUpdateRetried();
                pauseBeforeRetry(attempt);
            }
        }
        throw new ConflictException("Trip is being updated too often, try again");
    }

    // If a late ping arrives during this, the save is refused and the next pass picks the trip up.
    public TripResult recompute(TripRecord record) {
        TripEvidence evidence = readEvidence(record);
        metrics.recomputed();
        return computeAndSave(evidence, EvidenceHasher.hash(evidence, config.ruleVersion()), record.getEvidenceHash());
    }

    public TripResult get(String tenant, String tripId) {
        return readResult(load(tenant, tripId));
    }

    public boolean isOnTrip(String tenant, String tripId, String employeeId) {
        for (Milestone milestone : readEvidence(load(tenant, tripId)).milestones()) {
            if (employeeId.equals(milestone.employeeId())) {
                return true;
            }
        }
        return false;
    }

    public InvoiceResponse close(String tenant, String tripId, boolean reviewed) {
        Invoice invoice = store.close(tenant, tripId, reviewed);
        return InvoiceResponse.from(invoice, store.corrections(tenant, tripId));
    }

    public InvoiceResponse invoice(String tenant, String tripId) {
        Invoice invoice = store.findInvoice(tenant, tripId)
                .orElseThrow(() -> new NotFoundException("No invoice for this trip"));
        return InvoiceResponse.from(invoice, store.corrections(tenant, tripId));
    }

    private TripResult computeAndSave(TripEvidence evidence, String hash, String previousHash) {
        TripResult result = audited(calculator.calculate(evidence));
        store.save(evidence, result, hash, previousHash);
        metrics.tripProcessed(result.status().name());
        for (Segment segment : result.segments()) {
            if (segment.rule() == SegmentRule.REJECTED_DISTANCE || segment.rule() == SegmentRule.REJECTED_SPEED) {
                metrics.segmentRejected(segment.rule().name());
            }
        }
        log.info("trip computed tripId={} status={} meters={} providerCalls={}",
                result.tripId(), result.status(), result.totalMeters(), result.providerCalls());
        return result;
    }

    // Random pause so that updates colliding on one trip do not collide again straight away.
    private void pauseBeforeRetry(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(attempt * RETRY_PAUSE_MILLIS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // A trip the auditor disagrees with cannot be billed without a human looking at it.
    private TripResult audited(TripResult result) {
        List<String> problems = auditor.audit(result);
        if (problems.isEmpty()) {
            return result;
        }
        metrics.auditFailed();
        log.warn("trip failed audit tripId={} problems={}", result.tripId(), problems.size());
        return new TripResult(result.tripId(), result.ruleVersion(), result.totalMeters(), result.segments(),
                TripResult.Status.NEEDS_REVIEW, result.providerCalls());
    }

    private void requireValidPings(List<Ping> pings) {
        for (Ping ping : pings) {
            if (Math.abs(ping.latitude()) > 90 || Math.abs(ping.longitude()) > 180) {
                throw new BadRequestException("A ping has an impossible location");
            }
        }
    }

    private TripRecord load(String tenant, String tripId) {
        return store.find(tenant, tripId).orElseThrow(() -> new NotFoundException("Trip not found"));
    }

    private TripEvidence readEvidence(TripRecord record) {
        return mapper.readValue(record.getEvidenceJson(), TripEvidence.class);
    }

    private TripResult readResult(TripRecord record) {
        return mapper.readValue(record.getResultJson(), TripResult.class);
    }
}
