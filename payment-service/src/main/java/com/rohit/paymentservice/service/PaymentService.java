package com.rohit.paymentservice.service;

import com.rohit.paymentservice.dto.PaymentResponse;
import com.rohit.paymentservice.event.SeatReservedEvent;
import com.rohit.paymentservice.exception.ResourceNotFoundException;
import com.rohit.paymentservice.gateway.PaymentOutcome;
import com.rohit.paymentservice.gateway.PaymentSimulator;
import com.rohit.paymentservice.kafka.PaymentEventPublisher;
import com.rohit.paymentservice.model.Payment;
import com.rohit.paymentservice.model.PaymentStatus;
import com.rohit.paymentservice.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentSimulator paymentSimulator;
    private final PaymentEventPublisher paymentEventPublisher;

    // Kafka is at-least-once delivery, so redelivery of the same
    // seat-reserved event is expected — the unique constraint on
    // reservation_id plus this existence check make reprocessing a no-op
    // instead of a duplicate charge.
    @Transactional
    public void processSeatReserved(SeatReservedEvent event) {
        if (paymentRepository.findByReservationId(event.reservationId()).isPresent()) {
            log.info("Ignoring duplicate seat-reserved for reservation {} (already processed)", event.reservationId());
            return;
        }

        PaymentOutcome outcome = paymentSimulator.process(event.userId());

        Payment payment = Payment.builder()
                .reservationId(event.reservationId())
                .eventId(event.eventId())
                .seatId(event.seatId())
                .userId(event.userId())
                .status(outcome.confirmed() ? PaymentStatus.CONFIRMED : PaymentStatus.FAILED)
                .failureReason(outcome.failureReason())
                .build();
        Payment saved = paymentRepository.save(payment);

        if (outcome.confirmed()) {
            paymentEventPublisher.publishConfirmed(saved, event);
        } else {
            paymentEventPublisher.publishFailed(saved, event);
        }
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPaymentByReservationId(UUID reservationId) {
        Payment payment = paymentRepository.findByReservationId(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("No payment found for reservation: " + reservationId));
        return PaymentResponse.from(payment);
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> getPayments() {
        return paymentRepository.findAll().stream().map(PaymentResponse::from).collect(Collectors.toList());
    }
}
