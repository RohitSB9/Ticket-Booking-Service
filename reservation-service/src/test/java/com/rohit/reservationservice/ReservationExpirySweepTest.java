package com.rohit.reservationservice;

import com.rohit.reservationservice.client.EventServiceClient;
import com.rohit.reservationservice.dto.CreateReservationRequest;
import com.rohit.reservationservice.dto.ReservationResponse;
import com.rohit.reservationservice.dto.SeatDto;
import com.rohit.reservationservice.model.ReservationStatus;
import com.rohit.reservationservice.model.SeatStatus;
import com.rohit.reservationservice.repository.ReservationRepository;
import com.rohit.reservationservice.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Own Spring context, deliberately separate from
// ReservationControllerIntegrationTest: this class runs with a short TTL
// and a fast sweep tick so the test doesn't take a minute, and those
// settings would make PENDING reservations in the other test class's tests
// vanish mid-assertion if they shared a context.
@Testcontainers
@SpringBootTest(properties = {
        "reservation.pending-ttl-seconds=2",
        "reservation.expiry-sweep.fixed-delay-ms=1000"
})
class ReservationExpirySweepTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("reservations_expiry_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationRepository reservationRepository;

    @MockBean
    private EventServiceClient eventServiceClient;

    @Test
    void pendingReservation_pastTtl_getsExpiredAndSeatReleased() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();

        when(eventServiceClient.getSeat(eventId, seatId))
                .thenReturn(new SeatDto(seatId, "A1", "A", SeatStatus.AVAILABLE));

        ReservationResponse created = reservationService.createReservation(
                new CreateReservationRequest(eventId, seatId, "user-abandoned"));

        pollUntilStatus(created.id(), ReservationStatus.EXPIRED, 15);

        assertThat(reservationRepository.findById(created.id()).orElseThrow().getFailureReason())
                .contains("expired");
        verify(eventServiceClient).updateSeatStatus(eq(eventId), eq(seatId), eq(SeatStatus.AVAILABLE));
    }

    @Test
    void confirmedReservation_isNeverTouchedBySweepEvenThoughPastTtl() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();

        when(eventServiceClient.getSeat(eventId, seatId))
                .thenReturn(new SeatDto(seatId, "A1", "A", SeatStatus.AVAILABLE));

        ReservationResponse created = reservationService.createReservation(
                new CreateReservationRequest(eventId, seatId, "user-paid"));
        reservationService.confirmReservation(created.id());

        // Wait past both the TTL and a couple of sweep ticks — if the
        // sweep were only filtering on expiresAt and not status, this
        // would flip back to EXPIRED.
        Thread.sleep(4000);

        assertThat(reservationRepository.findById(created.id()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
    }

    private void pollUntilStatus(UUID reservationId, ReservationStatus expected, int timeoutSeconds) throws Exception {
        for (int i = 0; i < timeoutSeconds * 2; i++) {
            ReservationStatus status = reservationRepository.findById(reservationId)
                    .map(r -> r.getStatus())
                    .orElse(null);
            if (expected.equals(status)) {
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Reservation " + reservationId + " did not reach status " + expected + " in time");
    }
}
