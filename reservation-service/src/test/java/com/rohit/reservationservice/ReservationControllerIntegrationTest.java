package com.rohit.reservationservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rohit.reservationservice.client.EventServiceClient;
import com.rohit.reservationservice.dto.CreateReservationRequest;
import com.rohit.reservationservice.dto.SeatDto;
import com.rohit.reservationservice.model.SeatStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ReservationControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("reservations_test")
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

    // Event Service isn't running in this test, so its client is mocked —
    // these tests exercise reservation persistence and orchestration logic,
    // not the real HTTP call.
    @MockBean
    private EventServiceClient eventServiceClient;

    @Test
    void createReservation_reservesAvailableSeat() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();

        when(eventServiceClient.getSeat(eventId, seatId))
                .thenReturn(new SeatDto(seatId, "A1", "A", SeatStatus.AVAILABLE));

        CreateReservationRequest request = new CreateReservationRequest(eventId, seatId, "user-1");

        mockMvc.perform(post("/api/reservations")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.userId").value("user-1"));

        verify(eventServiceClient).updateSeatStatus(eventId, seatId, SeatStatus.RESERVED);
    }

    @Test
    void createReservation_rejectsUnavailableSeat() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();

        when(eventServiceClient.getSeat(eventId, seatId))
                .thenReturn(new SeatDto(seatId, "A1", "A", SeatStatus.SOLD));

        CreateReservationRequest request = new CreateReservationRequest(eventId, seatId, "user-1");

        mockMvc.perform(post("/api/reservations")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    void cancelReservation_releasesSeat() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();

        when(eventServiceClient.getSeat(eventId, seatId))
                .thenReturn(new SeatDto(seatId, "A1", "A", SeatStatus.AVAILABLE));

        CreateReservationRequest request = new CreateReservationRequest(eventId, seatId, "user-1");

        String response = mockMvc.perform(post("/api/reservations")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn().getResponse().getContentAsString();

        String reservationId = objectMapper.readTree(response).get("id").asText();

        mockMvc.perform(delete("/api/reservations/" + reservationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        verify(eventServiceClient).updateSeatStatus(eventId, seatId, SeatStatus.AVAILABLE);
    }

    @Test
    void getReservation_returnsNotFoundForUnknownId() throws Exception {
        mockMvc.perform(post("/api/reservations")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new CreateReservationRequest(UUID.randomUUID(), UUID.randomUUID(), ""))))
                .andExpect(status().isBadRequest());
    }
}
