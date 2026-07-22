package com.rohit.eventservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rohit.eventservice.dto.CreateEventRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class EventControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ticketing_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createEvent_generatesCorrectSeatCount() throws Exception {
        CreateEventRequest request = new CreateEventRequest(
                "Dune: Part Three",
                "Cineplex Yonge-Dundas",
                Instant.now().plus(7, ChronoUnit.DAYS),
                5,
                10
        );

        mockMvc.perform(post("/api/events")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalSeats").value(50))
                .andExpect(jsonPath("$.availableSeats").value(50))
                .andExpect(jsonPath("$.seats[0].seatLabel").value("A1"));
    }

    @Test
    void createEvent_rejectsPastStartDate() throws Exception {
        CreateEventRequest request = new CreateEventRequest(
                "Old Movie",
                "Cineplex Scarborough",
                Instant.now().minus(1, ChronoUnit.DAYS),
                2,
                2
        );

        mockMvc.perform(post("/api/events")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getSeats_filtersAvailableOnly() throws Exception {
        CreateEventRequest request = new CreateEventRequest(
                "Concert Night",
                "Scotiabank Arena",
                Instant.now().plus(3, ChronoUnit.DAYS),
                2,
                3
        );

        String response = mockMvc.perform(post("/api/events")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn().getResponse().getContentAsString();

        String eventId = objectMapper.readTree(response).get("id").asText();

        mockMvc.perform(get("/api/events/" + eventId + "/seats").param("availableOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6));
    }

    @Test
    void updateSeatStatus_persistsNewStatus() throws Exception {
        CreateEventRequest request = new CreateEventRequest(
                "Comedy Night",
                "Yuk Yuk's",
                Instant.now().plus(2, ChronoUnit.DAYS),
                1,
                1
        );

        String eventResponse = mockMvc.perform(post("/api/events")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn().getResponse().getContentAsString();

        var eventJson = objectMapper.readTree(eventResponse);
        String eventId = eventJson.get("id").asText();
        String seatId = eventJson.get("seats").get(0).get("id").asText();

        mockMvc.perform(patch("/api/events/" + eventId + "/seats/" + seatId)
                        .contentType("application/json")
                        .content("{\"status\":\"RESERVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESERVED"));

        mockMvc.perform(get("/api/events/" + eventId + "/seats/" + seatId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESERVED"));
    }

    @Test
    void updateSeatStatus_returnsNotFoundForUnknownSeat() throws Exception {
        CreateEventRequest request = new CreateEventRequest(
                "Trivia Night",
                "The Rec Room",
                Instant.now().plus(1, ChronoUnit.DAYS),
                1,
                1
        );

        String eventResponse = mockMvc.perform(post("/api/events")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn().getResponse().getContentAsString();

        String eventId = objectMapper.readTree(eventResponse).get("id").asText();

        mockMvc.perform(patch("/api/events/" + eventId + "/seats/" + java.util.UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"status\":\"RESERVED\"}"))
                .andExpect(status().isNotFound());
    }
}
