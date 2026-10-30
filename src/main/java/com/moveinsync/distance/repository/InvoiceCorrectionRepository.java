package com.moveinsync.distance.repository;

import com.moveinsync.distance.entity.InvoiceCorrection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// Database access for corrections.
public interface InvoiceCorrectionRepository extends JpaRepository<InvoiceCorrection, Long> {

    List<InvoiceCorrection> findByTenantIdAndTripIdOrderByCreatedAtAsc(String tenantId, String tripId);
}
