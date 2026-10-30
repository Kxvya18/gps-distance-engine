package com.moveinsync.distance.dto;

import com.moveinsync.distance.model.Milestone;
import com.moveinsync.distance.model.Ping;
import com.moveinsync.distance.model.TripEvidence;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

// JSON sent to the API. There is no tenant field: the tenant always comes from the login token.
public record TripRequest(
        @NotBlank @Size(max = 64) String tripId,
        @NotNull TripEvidence.Direction direction,
        @NotNull @Size(max = 20_000) List<Ping> pings,
        @NotNull @Size(max = 200) List<Milestone> milestones,
        @DecimalMin("-90") @DecimalMax("90") double officeLatitude,
        @DecimalMin("-180") @DecimalMax("180") double officeLongitude) {

    public TripEvidence toEvidence(String tenantId) {
        return new TripEvidence(tripId, tenantId, direction, pings, milestones, officeLatitude, officeLongitude);
    }
}
