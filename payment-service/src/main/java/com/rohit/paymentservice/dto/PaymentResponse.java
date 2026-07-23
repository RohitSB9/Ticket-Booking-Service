package com.rohit.paymentservice.dto;

import com.rohit.paymentservice.model.Payment;
import com.rohit.paymentservice.model.PaymentStatus;

import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID reservationId,
        UUID eventId,
        UUID seatId,
        String userId,
        PaymentStatus status,
        String failureReason,
        Instant processedAt
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getReservationId(),
                payment.getEventId(),
                payment.getSeatId(),
                payment.getUserId(),
                payment.getStatus(),
                payment.getFailureReason(),
                payment.getProcessedAt()
        );
    }
}
