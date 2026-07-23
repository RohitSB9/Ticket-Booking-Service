package com.rohit.paymentservice.event;

import java.time.Instant;
import java.util.UUID;

// Consumed from topic "seat-reserved", published by Reservation Service.
public record SeatReservedEvent(
        UUID reservationId,
        UUID eventId,
        UUID seatId,
        String userId,
        Instant reservedAt
) {
}
