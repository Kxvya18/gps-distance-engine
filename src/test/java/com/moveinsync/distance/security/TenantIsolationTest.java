package com.moveinsync.distance.security;

import com.moveinsync.distance.dto.TripRequest;
import com.moveinsync.distance.model.Milestone;
import com.moveinsync.distance.model.Ping;
import com.moveinsync.distance.model.TripEvidence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Tests that one client cannot read another client's data, and the role rules.
@SpringBootTest
@AutoConfigureMockMvc
class TenantIsolationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;

    private String login(String user) throws Exception {
        String body = mapper.writeValueAsString(Map.of("userId", user, "password", "test-password"));
        String response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode json = mapper.readTree(response);
        return "Bearer " + json.get("token").asString();
    }

    private TripRequest request(String tripId, String employee) {
        List<Ping> pings = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            pings.add(new Ping(12.9 + i * 0.0001, 77.6, i * 5000L, 10));
        }
        Milestone pickup = new Milestone(Milestone.Type.PICKUP, employee, 12.9, 77.6, 0L);
        return new TripRequest(tripId, TripEvidence.Direction.LOGIN, pings, List.of(pickup), List.of(), List.of(), 12.9, 77.6);
    }

    private String submit(String token, String employee) throws Exception {
        String tripId = "trip-" + UUID.randomUUID();
        mvc.perform(post("/api/trips").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request(tripId, employee))))
                .andExpect(status().isOk());
        return tripId;
    }

    @Test
    void anotherClientCannotReadTheTrip() throws Exception {
        String tripId = submit(login("admin-a"), "emp-1");
        mvc.perform(get("/api/trips/" + tripId).header("Authorization", login("admin-b")))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherClientCannotChangeOrCloseTheTrip() throws Exception {
        String tripId = submit(login("admin-a"), "emp-1");
        String other = login("admin-b");
        mvc.perform(post("/api/trips/" + tripId + "/close").header("Authorization", other))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/trips/" + tripId + "/late-pings").header("Authorization", other)
                        .contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isNotFound());
    }

    @Test
    void twoClientsCanUseTheSameTripIdSafely() throws Exception {
        String tripId = "shared-" + UUID.randomUUID();
        for (String user : List.of("admin-a", "admin-b")) {
            mvc.perform(post("/api/trips").header("Authorization", login(user)).contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(request(tripId, "emp-1"))))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void employeeSeesTheirOwnTripWithoutCoordinates() throws Exception {
        String tripId = submit(login("admin-a"), "emp-1");
        mvc.perform(get("/api/trips/" + tripId).header("Authorization", login("emp-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].fromLatitude").doesNotExist())
                .andExpect(jsonPath("$.segments[0].rule").exists());
    }

    @Test
    void employeeCannotSeeSomeoneElsesTrip() throws Exception {
        String tripId = submit(login("admin-a"), "someone-else");
        mvc.perform(get("/api/trips/" + tripId).header("Authorization", login("emp-1")))
                .andExpect(status().isNotFound());
    }

    @Test
    void employeeCannotSubmitTrips() throws Exception {
        mvc.perform(post("/api/trips").header("Authorization", login("emp-1")).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request("x", "emp-1"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorCannotSwitchTheProvider() throws Exception {
        mvc.perform(post("/api/admin/provider?down=true").header("Authorization", login("operator-a")))
                .andExpect(status().isForbidden());
    }

    @Test
    void noTokenAndBadPasswordAreRejected() throws Exception {
        mvc.perform(get("/api/trips/x")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("userId", "admin-a", "password", "wrong"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void metricsNeedARole() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").header("Authorization", login("emp-1"))).andExpect(status().isForbidden());
    }

    @Test
    void tooManyLatePingsAreRejected() throws Exception {
        String admin = login("admin-a");
        String tripId = submit(admin, "emp-1");
        List<Ping> tooMany = new ArrayList<>();
        for (int i = 0; i < 20_001; i++) {
            tooMany.add(new Ping(12.9, 77.6, i, 10));
        }
        mvc.perform(post("/api/trips/" + tripId + "/late-pings").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(tooMany)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidFieldsAreNamedWithoutTheirValues() throws Exception {
        String secret = "9876543210";
        String bad = "{\"tripId\":\"\",\"direction\":\"LOGIN\",\"pings\":[],\"milestones\":[],"
                + "\"plannedStops\":[],\"adHocRiders\":[],\"officeLatitude\":" + secret + ",\"officeLongitude\":1}";
        String response = mvc.perform(post("/api/trips").header("Authorization", login("admin-a"))
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid or missing fields: officeLatitude, tripId"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(response.contains(secret), response);
    }

    @Test
    void impossiblePingGivesAMeaningfulMessage() throws Exception {
        TripRequest good = request("trip-" + UUID.randomUUID(), "emp-1");
        TripRequest bad = new TripRequest(good.tripId(), good.direction(), List.of(new Ping(999, 77.6, 0, 10)),
                good.milestones(), good.plannedStops(), good.adHocRiders(), 12.9, 77.6);
        mvc.perform(post("/api/trips").header("Authorization", login("admin-a")).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(bad)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("A ping has an impossible location"));
    }

    @Test
    void errorsDoNotEchoRequestData() throws Exception {
        String secret = "9876543210-home-address";
        String bad = "{\"tripId\":\"\",\"direction\":\"" + secret + "\"}";
        String response = mvc.perform(post("/api/trips").header("Authorization", login("admin-a"))
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains(secret), response);
        assertTrue(response.length() > 0);
    }
}
