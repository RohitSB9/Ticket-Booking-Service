package com.rohit.reservationservice.event;

import java.time.Instant;
import java.util.UUID;

// Consumed from topic "payment-failed", published by Payment Service.
public record PaymentFailedEvent(
        UUID reservationId,
        UUID eventId,
        UUID seatId,
        String userId,
        String reason,
        Instant failedAt
) {
}
