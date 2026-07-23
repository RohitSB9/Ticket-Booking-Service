package com.rohit.reservationservice.event;

import java.time.Instant;
import java.util.UUID;

// Published to topic "seat-reserved" after a reservation is created.
// Consumed by Payment Service, which has its own mirrored copy of this
// record — no shared library between services in this project.
public record SeatReservedEvent(
        UUID reservationId,
        UUID eventId,
        UUID seatId,
        String userId,
        Instant reservedAt
) {
}
