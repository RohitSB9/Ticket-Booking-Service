package com.rohit.reservationservice.event;

import java.time.Instant;
import java.util.UUID;

// Consumed from topic "payment-confirmed", published by Payment Service.
public record PaymentConfirmedEvent(
        UUID reservationId,
        UUID eventId,
        UUID seatId,
        String userId,
        UUID paymentId,
        Instant confirmedAt
) {
}
