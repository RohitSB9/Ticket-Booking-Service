package com.rohit.eventservice.service;

import com.rohit.eventservice.dto.CreateEventRequest;
import com.rohit.eventservice.dto.EventResponse;
import com.rohit.eventservice.dto.SeatResponse;
import com.rohit.eventservice.exception.ResourceNotFoundException;
import com.rohit.eventservice.model.Event;
import com.rohit.eventservice.model.Seat;
import com.rohit.eventservice.model.SeatStatus;
import com.rohit.eventservice.repository.EventRepository;
import com.rohit.eventservice.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;

    @Transactional
    public EventResponse createEvent(CreateEventRequest request) {
        Event event = Event.builder()
                .name(request.name())
                .venue(request.venue())
                .startsAt(request.startsAt())
                .build();

        List<Seat> seats = generateSeats(event, request.rows(), request.seatsPerRow());
        event.setSeats(seats);

        Event saved = eventRepository.save(event);
        return EventResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<EventResponse> getAllEvents() {
        return eventRepository.findAll().stream()
                .map(EventResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public EventResponse getEvent(UUID eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + eventId));
        return EventResponse.from(event);
    }

    @Transactional(readOnly = true)
    public List<SeatResponse> getSeats(UUID eventId, boolean availableOnly) {
        if (!eventRepository.existsById(eventId)) {
            throw new ResourceNotFoundException("Event not found: " + eventId);
        }
        List<Seat> seats = availableOnly
                ? seatRepository.findByEventIdAndStatus(eventId, SeatStatus.AVAILABLE)
                : seatRepository.findByEventId(eventId);

        return seats.stream().map(SeatResponse::from).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public SeatResponse getSeat(UUID eventId, UUID seatId) {
        Seat seat = seatRepository.findByIdAndEventId(seatId, eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Seat not found: " + seatId));
        return SeatResponse.from(seat);
    }

    // Sets seat status directly with no transition validation. The Reservation
    // Service (step 2) owns the reserve/release decision; this is intentionally
    // a dumb write so the race condition it creates is visible before step 3
    // introduces the Redis lock that closes it.
    @Transactional
    public SeatResponse updateSeatStatus(UUID eventId, UUID seatId, SeatStatus status) {
        Seat seat = seatRepository.findByIdAndEventId(seatId, eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Seat not found: " + seatId));
        seat.setStatus(status);
        return SeatResponse.from(seatRepository.save(seat));
    }

    // Generates seat labels like A1, A2, ... A{seatsPerRow}, B1, B2, ...
    private List<Seat> generateSeats(Event event, int rows, int seatsPerRow) {
        List<Seat> seats = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            String rowLetter = String.valueOf((char) ('A' + r));
            for (int s = 1; s <= seatsPerRow; s++) {
                seats.add(Seat.builder()
                        .event(event)
                        .seatLabel(rowLetter + s)
                        .section(rowLetter)
                        .status(SeatStatus.AVAILABLE)
                        .build());
            }
        }
        return seats;
    }
}
