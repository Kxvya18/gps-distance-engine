package com.moveinsync.distance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

// Database row: a closed bill. It is never changed after it is written.
@Entity
@Table(name = "invoice", uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "trip_id"}))
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String tripId;

    private long billedMeters;
    private int ruleVersion;
    private Instant closedAt;

    protected Invoice() {
    }

    public Invoice(String tenantId, String tripId, long billedMeters, int ruleVersion) {
        this.tenantId = tenantId;
        this.tripId = tripId;
        this.billedMeters = billedMeters;
        this.ruleVersion = ruleVersion;
        this.closedAt = Instant.now();
    }

    public String getTripId() {
        return tripId;
    }

    public long getBilledMeters() {
        return billedMeters;
    }

    public int getRuleVersion() {
        return ruleVersion;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}
