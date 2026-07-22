package com.rohit.eventservice.dto;

import com.rohit.eventservice.model.Event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public record EventResponse(
        UUID id,
        String name,
        String venue,
        Instant startsAt,
        int totalSeats,
        int availableSeats,
        List<SeatResponse> seats
) {
    public static EventResponse from(Event event) {
        List<SeatResponse> seatResponses = event.getSeats().stream()
                .map(SeatResponse::from)
                .collect(Collectors.toList());

        long available = event.getSeats().stream()
                .filter(s -> s.getStatus().name().equals("AVAILABLE"))
                .count();

        return new EventResponse(
                event.getId(),
                event.getName(),
                event.getVenue(),
                event.getStartsAt(),
                event.getSeats().size(),
                (int) available,
                seatResponses
        );
    }
}
