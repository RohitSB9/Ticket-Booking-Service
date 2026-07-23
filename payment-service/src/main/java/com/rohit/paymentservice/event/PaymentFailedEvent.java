package com.rohit.paymentservice.event;

import java.time.Instant;
import java.util.UUID;

// Published to topic "payment-failed". Consumed by Reservation Service.
public record PaymentFailedEvent(
        UUID reservationId,
        UUID eventId,
        UUID seatId,
        String userId,
        String reason,
        Instant failedAt
) {
}
