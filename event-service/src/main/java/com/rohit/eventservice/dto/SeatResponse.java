package com.rohit.eventservice.dto;

import com.rohit.eventservice.model.Seat;
import com.rohit.eventservice.model.SeatStatus;

import java.util.UUID;

public record SeatResponse(
        UUID id,
        String seatLabel,
        String section,
        SeatStatus status
) {
    public static SeatResponse from(Seat seat) {
        return new SeatResponse(
                seat.getId(),
                seat.getSeatLabel(),
                seat.getSection(),
                seat.getStatus()
        );
    }
}
