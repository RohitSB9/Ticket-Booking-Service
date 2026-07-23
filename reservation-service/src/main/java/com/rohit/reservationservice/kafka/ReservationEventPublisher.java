package com.rohit.reservationservice.kafka;

import com.rohit.reservationservice.event.SeatReservedEvent;
import com.rohit.reservationservice.model.Reservation;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ReservationEventPublisher {

    private static final String TOPIC = "seat-reserved";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public ReservationEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishSeatReserved(Reservation reservation) {
        SeatReservedEvent event = new SeatReservedEvent(
                reservation.getId(),
                reservation.getEventId(),
                reservation.getSeatId(),
                reservation.getUserId(),
                Instant.now()
        );
        // Keyed by reservationId so retries/replays for the same reservation
        // land on the same partition and stay in order.
        kafkaTemplate.send(TOPIC, reservation.getId().toString(), event);
    }
}
