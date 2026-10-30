package com.moveinsync.distance.service;

import com.moveinsync.distance.entity.Invoice;
import com.moveinsync.distance.entity.InvoiceCorrection;
import com.moveinsync.distance.entity.TripRecord;
import com.moveinsync.distance.error.ConflictException;
import com.moveinsync.distance.error.NotFoundException;
import com.moveinsync.distance.error.StaleUpdateException;
import com.moveinsync.distance.model.TripEvidence;
import com.moveinsync.distance.model.TripResult;
import com.moveinsync.distance.monitoring.TripMetrics;
import com.moveinsync.distance.repository.InvoiceCorrectionRepository;
import com.moveinsync.distance.repository.InvoiceRepository;
import com.moveinsync.distance.repository.TripRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

// Saves a computed result. The result and any invoice correction commit in one transaction.
@Service
public class TripStore {

    private final TripRecordRepository trips;
    private final InvoiceRepository invoices;
    private final InvoiceCorrectionRepository corrections;
    private final ObjectMapper mapper;
    private final TripMetrics metrics;

    public TripStore(TripRecordRepository trips, InvoiceRepository invoices, InvoiceCorrectionRepository corrections,
                     ObjectMapper mapper, TripMetrics metrics) {
        this.trips = trips;
        this.invoices = invoices;
        this.corrections = corrections;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    // previousHash is the evidence this result was computed on top of, null for a new trip.
    // Computing happens outside the transaction, so without this check two late-ping updates
    // could read the same trip and the second save would silently drop the first one's pings.
    @Transactional
    public void save(TripEvidence evidence, TripResult result, String evidenceHash, String previousHash) {
        String tenant = evidence.tenantId();
        String tripId = evidence.tripId();

        TripRecord record = trips.lockTrip(tenant, tripId)
                .orElseGet(() -> new TripRecord(tenant, tripId));
        if (!Objects.equals(record.getEvidenceHash(), previousHash)) {
            throw new StaleUpdateException("Trip changed while it was being computed");
        }
        record.update(result.ruleVersion(), evidenceHash, result.totalMeters(), result.status().name(),
                result.providerCalls(), mapper.writeValueAsString(evidence), mapper.writeValueAsString(result));
        trips.save(record);

        // An estimate never goes on a bill. The correction waits for the real recompute.
        if (result.status() != TripResult.Status.DEGRADED) {
            invoices.findByTenantIdAndTripId(tenant, tripId)
                    .ifPresent(invoice -> addCorrection(invoice, tenant, tripId, result.totalMeters(), evidenceHash));
        }
    }

    private void addCorrection(Invoice invoice, String tenant, String tripId, long newMeters, String evidenceHash) {
        long billedSoFar = invoice.getBilledMeters();
        for (InvoiceCorrection correction : corrections.findByTenantIdAndTripIdOrderByCreatedAtAsc(tenant, tripId)) {
            billedSoFar += correction.getDeltaMeters();
        }
        long delta = newMeters - billedSoFar;
        if (delta != 0) {
            corrections.save(new InvoiceCorrection(tenant, tripId, delta, evidenceHash));
            metrics.correctionIssued();
        }
    }

    @Transactional(readOnly = true)
    public Optional<TripRecord> find(String tenant, String tripId) {
        return trips.findByTenantIdAndTripId(tenant, tripId);
    }

    // Closing locks the trip row, so a late-ping update lands either before the bill is
    // written or after it, and then sees the invoice and adds its correction.
    @Transactional
    public Invoice close(String tenant, String tripId, boolean reviewed) {
        TripRecord record = trips.lockTrip(tenant, tripId)
                .orElseThrow(() -> new NotFoundException("Trip not found"));
        if ("DEGRADED".equals(record.getStatus())) {
            throw new ConflictException("Trip uses estimated distances and cannot be billed until it is recomputed");
        }
        if ("NEEDS_REVIEW".equals(record.getStatus()) && !reviewed) {
            throw new ConflictException("Trip needs review before it can be billed");
        }
        return invoices.findByTenantIdAndTripId(tenant, tripId)
                .orElseGet(() -> invoices.save(new Invoice(tenant, tripId, record.getTotalMeters(), record.getRuleVersion())));
    }

    @Transactional(readOnly = true)
    public Optional<Invoice> findInvoice(String tenant, String tripId) {
        return invoices.findByTenantIdAndTripId(tenant, tripId);
    }

    @Transactional(readOnly = true)
    public List<InvoiceCorrection> corrections(String tenant, String tripId) {
        return corrections.findByTenantIdAndTripIdOrderByCreatedAtAsc(tenant, tripId);
    }
}
