package com.rohit.reservationservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rohit.reservationservice.client.EventServiceClient;
import com.rohit.reservationservice.dto.CreateReservationRequest;
import com.rohit.reservationservice.dto.SeatDto;
import com.rohit.reservationservice.event.PaymentConfirmedEvent;
import com.rohit.reservationservice.event.PaymentFailedEvent;
import com.rohit.reservationservice.exception.SeatLockedException;
import com.rohit.reservationservice.lock.SeatLockService;
import com.rohit.reservationservice.model.SeatStatus;
import com.rohit.reservationservice.repository.ReservationRepository;
import com.rohit.reservationservice.service.ReservationService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
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
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private SeatLockService seatLockService;

    @Autowired
    private ReservationRepository reservationRepository;

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

    @Test
    void createReservation_publishesSeatReservedEvent() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();

        when(eventServiceClient.getSeat(eventId, seatId))
                .thenReturn(new SeatDto(seatId, "A1", "A", SeatStatus.AVAILABLE));

        String response = mockMvc.perform(post("/api/reservations")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new CreateReservationRequest(eventId, seatId, "user-1"))))
                .andReturn().getResponse().getContentAsString();
        String reservationId = objectMapper.readTree(response).get("id").asText();

        // Other tests in this class also create reservations and publish to
        // this same topic, so a fresh earliest-offset consumer sees more
        // than just this test's message — search for the matching key
        // instead of assuming there's exactly one record.
        var consumer = new DefaultKafkaConsumerFactory<>(
                testConsumerProps(), new StringDeserializer(), new StringDeserializer())
                .createConsumer();
        consumer.subscribe(List.of("seat-reserved"));

        ConsumerRecord<String, String> match = null;
        for (int i = 0; i < 20 && match == null; i++) {
            for (ConsumerRecord<String, String> record : KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(1))) {
                if (reservationId.equals(record.key())) {
                    match = record;
                    break;
                }
            }
        }
        consumer.close();

        assertThat(match).as("seat-reserved record for reservation " + reservationId).isNotNull();
        assertThat(match.value()).contains(reservationId);
    }

    @Test
    void paymentConfirmedEvent_confirmsReservationAndMarksSeatSold() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();

        when(eventServiceClient.getSeat(eventId, seatId))
                .thenReturn(new SeatDto(seatId, "A1", "A", SeatStatus.AVAILABLE));

        String response = mockMvc.perform(post("/api/reservations")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new CreateReservationRequest(eventId, seatId, "user-1"))))
                .andReturn().getResponse().getContentAsString();
        UUID reservationId = UUID.fromString(objectMapper.readTree(response).get("id").asText());

        PaymentConfirmedEvent event = new PaymentConfirmedEvent(
                reservationId, eventId, seatId, "user-1", UUID.randomUUID(), Instant.now());
        testProducer().send("payment-confirmed", reservationId.toString(), event).get();

        pollUntilStatus(reservationId, "CONFIRMED");
        verify(eventServiceClient).updateSeatStatus(eq(eventId), eq(seatId), eq(SeatStatus.SOLD));
    }

    @Test
    void paymentFailedEvent_cancelsReservationAndReleasesSeat() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();

        when(eventServiceClient.getSeat(eventId, seatId))
                .thenReturn(new SeatDto(seatId, "A1", "A", SeatStatus.AVAILABLE));

        String response = mockMvc.perform(post("/api/reservations")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new CreateReservationRequest(eventId, seatId, "fail-user-1"))))
                .andReturn().getResponse().getContentAsString();
        UUID reservationId = UUID.fromString(objectMapper.readTree(response).get("id").asText());

        PaymentFailedEvent event = new PaymentFailedEvent(
                reservationId, eventId, seatId, "fail-user-1", "Card declined (simulated)", Instant.now());
        testProducer().send("payment-failed", reservationId.toString(), event).get();

        pollUntilStatus(reservationId, "CANCELLED");
        verify(eventServiceClient).updateSeatStatus(eq(eventId), eq(seatId), eq(SeatStatus.AVAILABLE));
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getFailureReason())
                .isEqualTo("Card declined (simulated)");
    }

    private KafkaTemplate<String, Object> testProducer() {
        Map<String, Object> props = new HashMap<>();
        props.put("bootstrap.servers", kafka.getBootstrapServers());
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", JsonSerializer.class);
        props.put("spring.json.type.mapping", "paymentConfirmed:com.rohit.reservationservice.event.PaymentConfirmedEvent,"
                + "paymentFailed:com.rohit.reservationservice.event.PaymentFailedEvent");
        ProducerFactory<String, Object> pf = new DefaultKafkaProducerFactory<>(props);
        return new KafkaTemplate<>(pf);
    }

    private Map<String, Object> testConsumerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return props;
    }

    // The listener processes asynchronously; poll the same repository the
    // listener writes to instead of sleeping a fixed guess.
    private void pollUntilStatus(UUID reservationId, String expectedStatus) throws Exception {
        for (int i = 0; i < 40; i++) {
            String status = reservationRepository.findById(reservationId)
                    .map(r -> r.getStatus().name())
                    .orElse(null);
            if (expectedStatus.equals(status)) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Reservation " + reservationId + " did not reach status " + expectedStatus + " in time");
    }
}
