package com.rohit.paymentservice;

import com.rohit.paymentservice.event.SeatReservedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class PaymentServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payments_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private MockMvc mockMvc;

    private KafkaTemplate<String, Object> testProducer() {
        Map<String, Object> props = new HashMap<>();
        props.put("bootstrap.servers", kafka.getBootstrapServers());
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", JsonSerializer.class);
        props.put("spring.json.type.mapping", "seatReserved:com.rohit.paymentservice.event.SeatReservedEvent");
        ProducerFactory<String, Object> pf = new DefaultKafkaProducerFactory<>(props);
        return new KafkaTemplate<>(pf);
    }

    @Test
    void seatReserved_forNormalUser_producesConfirmedPayment() throws Exception {
        UUID reservationId = UUID.randomUUID();
        SeatReservedEvent event = new SeatReservedEvent(
                reservationId, UUID.randomUUID(), UUID.randomUUID(), "user-1", Instant.now());

        testProducer().send("seat-reserved", reservationId.toString(), event).get();

        pollUntilPaymentExists(reservationId);

        mockMvc.perform(get("/api/payments/" + reservationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.failureReason").doesNotExist());
    }

    @Test
    void seatReserved_forForcedFailureUser_producesFailedPayment() throws Exception {
        UUID reservationId = UUID.randomUUID();
        SeatReservedEvent event = new SeatReservedEvent(
                reservationId, UUID.randomUUID(), UUID.randomUUID(), "fail-user-2", Instant.now());

        testProducer().send("seat-reserved", reservationId.toString(), event).get();

        pollUntilPaymentExists(reservationId);

        mockMvc.perform(get("/api/payments/" + reservationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("Card declined (simulated)"));
    }

    // The listener processes asynchronously; poll the REST read path (which
    // hits the same Postgres row the listener writes) instead of sleeping a
    // fixed guess.
    private void pollUntilPaymentExists(UUID reservationId) throws Exception {
        for (int i = 0; i < 40; i++) {
            int status = mockMvc.perform(get("/api/payments/" + reservationId))
                    .andReturn().getResponse().getStatus();
            if (status == 200) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Payment for reservation " + reservationId + " was not created in time");
    }
}
