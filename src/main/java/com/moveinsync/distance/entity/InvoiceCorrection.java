package com.moveinsync.distance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

// Database row: a plus or minus correction to a closed invoice.
// One evidence hash can only ever produce one correction.
@Entity
@Table(name = "invoice_correction",
        uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "trip_id", "evidence_hash"}))
public class InvoiceCorrection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String tripId;

    private long deltaMeters;
    private String evidenceHash;
    private Instant createdAt;

    protected InvoiceCorrection() {
    }

    public InvoiceCorrection(String tenantId, String tripId, long deltaMeters, String evidenceHash) {
        this.tenantId = tenantId;
        this.tripId = tripId;
        this.deltaMeters = deltaMeters;
        this.evidenceHash = evidenceHash;
        this.createdAt = Instant.now();
    }

    public long getDeltaMeters() {
        return deltaMeters;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
