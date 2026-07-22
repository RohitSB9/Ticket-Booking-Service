package com.rohit.reservationservice.dto;

import com.rohit.reservationservice.model.Reservation;
import com.rohit.reservationservice.model.ReservationStatus;

import java.time.Instant;
import java.util.UUID;

public record ReservationResponse(
        UUID id,
        UUID eventId,
        UUID seatId,
        String userId,
        ReservationStatus status,
        Instant createdAt
) {
    public static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getEventId(),
                reservation.getSeatId(),
                reservation.getUserId(),
                reservation.getStatus(),
                reservation.getCreatedAt()
        );
    }
}
