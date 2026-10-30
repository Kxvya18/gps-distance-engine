package com.moveinsync.distance.service;

import com.moveinsync.distance.dto.TripRequest;
import com.moveinsync.distance.model.Ping;
import com.moveinsync.distance.model.TripEvidence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Failure test: the database cannot be reached.
@SpringBootTest
@AutoConfigureMockMvc
class DatabaseDownTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;
    @MockitoBean
    private TripStore store;

    @Test
    void callerGetsARetryableErrorWithNoInternalDetails() throws Exception {
        when(store.find(anyString(), anyString()))
                .thenThrow(new CannotCreateTransactionException("connection to db-host-secret refused"));

        String login = mapper.writeValueAsString(Map.of("userId", "admin-a", "password", "test-password"));
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                .andReturn().getResponse().getContentAsString();
        String token = "Bearer " + mapper.readTree(body).get("token").asString();

        TripRequest trip = new TripRequest("db-down", TripEvidence.Direction.LOGIN, List.of(new Ping(12.9, 77.6, 0, 10)),
                List.of(), List.of(), List.of(), 12.9, 77.6);
        String response = mvc.perform(post("/api/trips").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(trip)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Temporarily unavailable, safe to retry"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(response.contains("db-host-secret"), response);
    }
}
