package com.rohit.eventservice.dto;

import com.rohit.eventservice.model.SeatStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateSeatStatusRequest(

        @NotNull(message = "status is required")
        SeatStatus status
) {
}
