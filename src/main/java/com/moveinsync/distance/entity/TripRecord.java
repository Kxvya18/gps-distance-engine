package com.moveinsync.distance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;

// Database row: the latest result for one trip, plus the evidence needed to recompute it.
@Entity
@Table(name = "trip_record", uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "trip_id"}))
public class TripRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Optimistic lock: two recomputes of one trip cannot silently overwrite each other.
    @Version
    private long version;

    @Column(nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String tripId;

    private int ruleVersion;
    private String evidenceHash;
    private long totalMeters;
    private String status;
    private int providerCalls;
    private Instant updatedAt;

    @Column(columnDefinition = "text")
    private String evidenceJson;

    @Column(columnDefinition = "text")
    private String resultJson;

    protected TripRecord() {
    }

    public TripRecord(String tenantId, String tripId) {
        this.tenantId = tenantId;
        this.tripId = tripId;
    }

    public void update(int ruleVersion, String evidenceHash, long totalMeters, String status, int providerCalls,
                       String evidenceJson, String resultJson) {
        this.ruleVersion = ruleVersion;
        this.evidenceHash = evidenceHash;
        this.totalMeters = totalMeters;
        this.status = status;
        this.providerCalls = providerCalls;
        this.evidenceJson = evidenceJson;
        this.resultJson = resultJson;
        this.updatedAt = Instant.now();
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getTripId() {
        return tripId;
    }

    public int getRuleVersion() {
        return ruleVersion;
    }

    public String getEvidenceHash() {
        return evidenceHash;
    }

    public long getTotalMeters() {
        return totalMeters;
    }

    public String getStatus() {
        return status;
    }

    public String getEvidenceJson() {
        return evidenceJson;
    }

    public String getResultJson() {
        return resultJson;
    }
}
