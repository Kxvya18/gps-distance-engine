package com.moveinsync.distance.repository;

import com.moveinsync.distance.entity.TripRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

// Database access for trip results. Every lookup by trip includes the tenant.
public interface TripRecordRepository extends JpaRepository<TripRecord, Long> {

    Optional<TripRecord> findByTenantIdAndTripId(String tenantId, String tripId);

    // Holds the row until the transaction ends, so a save and a close of one trip take turns.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TripRecord t where t.tenantId = :tenantId and t.tripId = :tripId")
    Optional<TripRecord> lockTrip(@Param("tenantId") String tenantId, @Param("tripId") String tripId);

    List<TripRecord> findByStatus(String status, Pageable page);

    long countByStatus(String status);

    Optional<TripRecord> findFirstByStatusOrderByDegradedSinceAsc(String status);
}
