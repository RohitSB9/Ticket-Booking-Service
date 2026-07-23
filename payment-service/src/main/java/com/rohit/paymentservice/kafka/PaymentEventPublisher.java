package com.rohit.paymentservice.kafka;

import com.rohit.paymentservice.event.PaymentConfirmedEvent;
import com.rohit.paymentservice.event.PaymentFailedEvent;
import com.rohit.paymentservice.event.SeatReservedEvent;
import com.rohit.paymentservice.model.Payment;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public PaymentEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishConfirmed(Payment payment, SeatReservedEvent source) {
        PaymentConfirmedEvent event = new PaymentConfirmedEvent(
                source.reservationId(),
                source.eventId(),
                source.seatId(),
                source.userId(),
                payment.getId(),
                payment.getProcessedAt()
        );
        kafkaTemplate.send("payment-confirmed", source.reservationId().toString(), event);
    }

    public void publishFailed(Payment payment, SeatReservedEvent source) {
        PaymentFailedEvent event = new PaymentFailedEvent(
                source.reservationId(),
                source.eventId(),
                source.seatId(),
                source.userId(),
                payment.getFailureReason(),
                payment.getProcessedAt()
        );
        kafkaTemplate.send("payment-failed", source.reservationId().toString(), event);
    }
}
