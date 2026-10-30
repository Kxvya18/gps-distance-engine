package com.moveinsync.distance.controller;

import com.moveinsync.distance.dto.InvoiceResponse;
import com.moveinsync.distance.dto.TripRequest;
import com.moveinsync.distance.dto.TripResponse;
import com.moveinsync.distance.error.NotFoundException;
import com.moveinsync.distance.model.Ping;
import com.moveinsync.distance.model.TripResult;
import com.moveinsync.distance.routing.FakeRoutingProvider;
import com.moveinsync.distance.security.CurrentUser;
import com.moveinsync.distance.service.TripService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

// API endpoints for trips. The tenant always comes from the token.
@RestController
public class TripController {

    private final TripService trips;
    private final FakeRoutingProvider provider;

    public TripController(TripService trips, FakeRoutingProvider provider) {
        this.trips = trips;
        this.provider = provider;
    }

    @PostMapping("/api/trips")
    public TripResponse submit(@Valid @RequestBody TripRequest request, @AuthenticationPrincipal Jwt token) {
        CurrentUser user = CurrentUser.from(token);
        return TripResponse.from(trips.submit(request.toEvidence(user.tenantId())), true);
    }

    @GetMapping("/api/trips/{tripId}")
    public TripResponse get(@PathVariable String tripId, @AuthenticationPrincipal Jwt token) {
        CurrentUser user = CurrentUser.from(token);
        TripResult result = trips.get(user.tenantId(), tripId);
        if (user.isEmployee()) {
            requireOnTrip(user, tripId);
        }
        return TripResponse.from(result, !user.isEmployee());
    }

    @PostMapping("/api/trips/{tripId}/late-pings")
    public TripResponse latePings(@PathVariable String tripId, @RequestBody @Size(max = 20_000) List<Ping> pings,
                                  @AuthenticationPrincipal Jwt token) {
        CurrentUser user = CurrentUser.from(token);
        return TripResponse.from(trips.addLatePings(user.tenantId(), tripId, pings), true);
    }

    @PostMapping("/api/trips/{tripId}/close")
    public InvoiceResponse close(@PathVariable String tripId,
                                 @RequestParam(defaultValue = "false") boolean reviewed,
                                 @AuthenticationPrincipal Jwt token) {
        return trips.close(CurrentUser.from(token).tenantId(), tripId, reviewed);
    }

    @GetMapping("/api/trips/{tripId}/invoice")
    public InvoiceResponse invoice(@PathVariable String tripId, @AuthenticationPrincipal Jwt token) {
        CurrentUser user = CurrentUser.from(token);
        if (user.isEmployee()) {
            throw new NotFoundException("Trip not found");
        }
        return trips.invoice(user.tenantId(), tripId);
    }

    // Lets the demo and failure tests switch the fake provider off and on.
    @PostMapping("/api/admin/provider")
    public Map<String, Boolean> setProviderDown(@RequestParam boolean down) {
        provider.setDown(down);
        return Map.of("down", provider.isDown());
    }

    private void requireOnTrip(CurrentUser user, String tripId) {
        if (!trips.isOnTrip(user.tenantId(), tripId, user.userId())) {
            throw new NotFoundException("Trip not found");
        }
    }
}
