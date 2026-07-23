package com.rohit.paymentservice.event;

import java.time.Instant;
import java.util.UUID;

// Published to topic "payment-confirmed". Consumed by Reservation Service.
public record PaymentConfirmedEvent(
        UUID reservationId,
        UUID eventId,
        UUID seatId,
        String userId,
        UUID paymentId,
        Instant confirmedAt
) {
}
