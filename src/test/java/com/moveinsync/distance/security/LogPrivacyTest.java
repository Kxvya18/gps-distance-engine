package com.moveinsync.distance.security;

import com.moveinsync.distance.dto.TripRequest;
import com.moveinsync.distance.model.AdHocRider;
import com.moveinsync.distance.model.Milestone;
import com.moveinsync.distance.model.Ping;
import com.moveinsync.distance.model.TripEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Tests that a rider's home location and id never reach the logs, on success or failure.
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class LogPrivacyTest {

    private static final String HOME_LATITUDE = "13.123456";
    private static final String RIDER = "rider-private-id";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;

    @Test
    void homeLocationAndRiderIdStayOutOfTheLogs(CapturedOutput output) throws Exception {
        String login = mapper.writeValueAsString(Map.of("userId", "admin-a", "password", "test-password"));
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                .andReturn().getResponse().getContentAsString();
        String token = "Bearer " + mapper.readTree(body).get("token").asString();

        double home = Double.parseDouble(HOME_LATITUDE);
        List<Ping> pings = List.of(new Ping(12.9, 77.6, 0, 10), new Ping(12.9001, 77.6, 5000, 10));
        Milestone pickup = new Milestone(Milestone.Type.PICKUP, RIDER, null, null, null);
        TripRequest trip = new TripRequest("log-privacy", TripEvidence.Direction.LOGIN, pings, List.of(pickup),
                List.of(), List.of(new AdHocRider(RIDER, home, 77.6, 3000)), 12.9, 77.6);

        mvc.perform(post("/api/trips").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(trip)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/trips").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tripId\":\"" + RIDER + "\",\"direction\":\"" + HOME_LATITUDE + "\"}"))
                .andExpect(status().isBadRequest());

        assertTrue(output.getOut().contains("tripId=log-privacy"), "the trip should be logged by id");
        assertFalse(output.getAll().contains(HOME_LATITUDE));
        assertFalse(output.getAll().contains(RIDER));
    }
}
