package com.rohit.eventservice.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record CreateEventRequest(

        @NotBlank(message = "name is required")
        String name,

        @NotBlank(message = "venue is required")
        String venue,

        @NotNull(message = "startsAt is required")
        @Future(message = "startsAt must be in the future")
        Instant startsAt,

        @NotNull(message = "rows is required")
        @Min(value = 1, message = "rows must be at least 1")
        Integer rows,

        @NotNull(message = "seatsPerRow is required")
        @Min(value = 1, message = "seatsPerRow must be at least 1")
        Integer seatsPerRow
) {
}
