package com.moveinsync.distance.repository;

import com.moveinsync.distance.entity.TripRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

// Database access for trip results. Every lookup by trip includes the tenant.
public interface TripRecordRepository extends JpaRepository<TripRecord, Long> {

    Optional<TripRecord> findByTenantIdAndTripId(String tenantId, String tripId);

    List<TripRecord> findByStatus(String status, Pageable page);

    long countByStatus(String status);
}
