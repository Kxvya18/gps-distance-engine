package com.moveinsync.distance.repository;

import com.moveinsync.distance.entity.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

// Database access for invoices.
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    Optional<Invoice> findByTenantIdAndTripId(String tenantId, String tripId);
}
