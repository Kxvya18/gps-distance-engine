package com.moveinsync.distance.dto;

import com.moveinsync.distance.entity.Invoice;
import com.moveinsync.distance.entity.InvoiceCorrection;

import java.util.ArrayList;
import java.util.List;

// JSON for a closed invoice and its corrections, shown as signed kilometres.
public record InvoiceResponse(String tripId, long billedMeters, int ruleVersion, List<CorrectionLine> corrections,
                              long currentMeters) {

    public record CorrectionLine(long deltaMeters, String deltaKm) {
    }

    public static InvoiceResponse from(Invoice invoice, List<InvoiceCorrection> corrections) {
        List<CorrectionLine> lines = new ArrayList<>();
        long current = invoice.getBilledMeters();
        for (InvoiceCorrection correction : corrections) {
            current += correction.getDeltaMeters();
            lines.add(new CorrectionLine(correction.getDeltaMeters(),
                    String.format("%+.1f km", correction.getDeltaMeters() / 1000.0)));
        }
        return new InvoiceResponse(invoice.getTripId(), invoice.getBilledMeters(), invoice.getRuleVersion(), lines, current);
    }
}
