package com.rohit.reservationservice.dto;

import com.rohit.reservationservice.model.SeatStatus;

import java.util.UUID;

// Mirrors Event Service's SeatResponse — this is the shape returned by
// GET/PATCH /api/events/{eventId}/seats/{seatId}.
public record SeatDto(
        UUID id,
        String seatLabel,
        String section,
        SeatStatus status
) {
}
