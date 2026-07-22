package com.rohit.reservationservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rohit.reservationservice.client.EventServiceClient;
import com.rohit.reservationservice.dto.CreateReservationRequest;
import com.rohit.reservationservice.dto.SeatDto;
import com.rohit.reservationservice.exception.SeatLockedException;
import com.rohit.reservationservice.lock.SeatLockService;
import com.rohit.reservationservice.model.SeatStatus;
import com.rohit.reservationservice.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private SeatLockService seatLockService;

    // Event Service isn't running in this test, so its client is mocked —
    // these tests exercise reservation persistence and orchestration logic,
    // not the real HTTP call. Redis, on the other hand, is real
    // (Testcontainers) since it's the actual mechanism under test here.
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

    // The money test for step 3: two concurrent createReservation calls for
    // the same seat must not both win. The artificial delay in the mocked
    // getSeat call widens the window between "lock acquired" and "lock
    // released" so the second thread's tryLock reliably lands while the
    // first still holds it, proving the lock — not luck — is what
    // serializes them.
    @Test
    void createReservation_secondConcurrentAttemptForSameSeatIsRejected() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();

        when(eventServiceClient.getSeat(eventId, seatId)).thenAnswer(invocation -> {
            Thread.sleep(300);
            return new SeatDto(seatId, "A1", "A", SeatStatus.AVAILABLE);
        });

        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Callable<Object> attempt1 = () -> {
            bothReady.countDown();
            go.await();
            return reservationService.createReservation(
                    new CreateReservationRequest(eventId, seatId, "user-1"));
        };
        Callable<Object> attempt2 = () -> {
            bothReady.countDown();
            go.await();
            return reservationService.createReservation(
                    new CreateReservationRequest(eventId, seatId, "user-2"));
        };

        Future<Object> f1 = pool.submit(attempt1);
        Future<Object> f2 = pool.submit(attempt2);
        bothReady.await(2, TimeUnit.SECONDS);
        go.countDown();

        int succeeded = 0;
        int lockedOut = 0;
        for (Future<Object> f : List.of(f1, f2)) {
            try {
                f.get(5, TimeUnit.SECONDS);
                succeeded++;
            } catch (ExecutionException e) {
                if (e.getCause() instanceof SeatLockedException) {
                    lockedOut++;
                } else {
                    throw e;
                }
            }
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(1);
        assertThat(lockedOut).isEqualTo(1);
    }

    @Test
    void seatLockService_secondTryLockFailsWhileHeld() {
        UUID seatId = UUID.randomUUID();

        assertThat(seatLockService.tryLock(seatId, "owner-1")).isTrue();
        assertThat(seatLockService.tryLock(seatId, "owner-2")).isFalse();

        seatLockService.unlock(seatId, "owner-1");

        assertThat(seatLockService.tryLock(seatId, "owner-2")).isTrue();
        seatLockService.unlock(seatId, "owner-2");
    }

    @Test
    void seatLockService_unlockWithWrongOwnerDoesNotRelease() {
        UUID seatId = UUID.randomUUID();

        assertThat(seatLockService.tryLock(seatId, "owner-1")).isTrue();

        seatLockService.unlock(seatId, "someone-else");

        assertThat(seatLockService.tryLock(seatId, "owner-2")).isFalse();

        seatLockService.unlock(seatId, "owner-1");
    }
}
