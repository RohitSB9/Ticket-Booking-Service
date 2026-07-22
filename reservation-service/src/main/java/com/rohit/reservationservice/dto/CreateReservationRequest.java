package com.rohit.reservationservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateReservationRequest(

        @NotNull(message = "eventId is required")
        UUID eventId,

        @NotNull(message = "seatId is required")
        UUID seatId,

        @NotBlank(message = "userId is required")
        String userId
) {
}
