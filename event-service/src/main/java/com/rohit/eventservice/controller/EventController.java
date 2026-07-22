package com.rohit.eventservice.controller;

import com.rohit.eventservice.dto.CreateEventRequest;
import com.rohit.eventservice.dto.EventResponse;
import com.rohit.eventservice.dto.SeatResponse;
import com.rohit.eventservice.dto.UpdateSeatStatusRequest;
import com.rohit.eventservice.service.EventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @PostMapping
    public ResponseEntity<EventResponse> createEvent(@Valid @RequestBody CreateEventRequest request) {
        EventResponse response = eventService.createEvent(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<EventResponse>> getAllEvents() {
        return ResponseEntity.ok(eventService.getAllEvents());
    }

    @GetMapping("/{eventId}")
    public ResponseEntity<EventResponse> getEvent(@PathVariable UUID eventId) {
        return ResponseEntity.ok(eventService.getEvent(eventId));
    }

    @GetMapping("/{eventId}/seats")
    public ResponseEntity<List<SeatResponse>> getSeats(
            @PathVariable UUID eventId,
            @RequestParam(name = "availableOnly", defaultValue = "false") boolean availableOnly
    ) {
        return ResponseEntity.ok(eventService.getSeats(eventId, availableOnly));
    }

    @GetMapping("/{eventId}/seats/{seatId}")
    public ResponseEntity<SeatResponse> getSeat(@PathVariable UUID eventId, @PathVariable UUID seatId) {
        return ResponseEntity.ok(eventService.getSeat(eventId, seatId));
    }

    @PatchMapping("/{eventId}/seats/{seatId}")
    public ResponseEntity<SeatResponse> updateSeatStatus(
            @PathVariable UUID eventId,
            @PathVariable UUID seatId,
            @Valid @RequestBody UpdateSeatStatusRequest request
    ) {
        return ResponseEntity.ok(eventService.updateSeatStatus(eventId, seatId, request.status()));
    }
}
