package com.rohit.reservationservice.service;

import com.rohit.reservationservice.client.EventServiceClient;
import com.rohit.reservationservice.dto.CreateReservationRequest;
import com.rohit.reservationservice.dto.ReservationResponse;
import com.rohit.reservationservice.dto.SeatDto;
import com.rohit.reservationservice.exception.ResourceNotFoundException;
import com.rohit.reservationservice.exception.SeatUnavailableException;
import com.rohit.reservationservice.model.Reservation;
import com.rohit.reservationservice.model.ReservationStatus;
import com.rohit.reservationservice.model.SeatStatus;
import com.rohit.reservationservice.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final EventServiceClient eventServiceClient;

    // Check-then-act against Event Service with no lock in between: two
    // concurrent requests for the same seat can both pass the availability
    // check and both succeed here. That race is intentional for step 2 —
    // step 3 closes it with a Redis lock around this method.
    @Transactional
    public ReservationResponse createReservation(CreateReservationRequest request) {
        SeatDto seat = eventServiceClient.getSeat(request.eventId(), request.seatId());
        if (seat.status() != SeatStatus.AVAILABLE) {
            throw new SeatUnavailableException("Seat " + seat.seatLabel() + " is not available");
        }

        Reservation reservation = Reservation.builder()
                .eventId(request.eventId())
                .seatId(request.seatId())
                .userId(request.userId())
                .status(ReservationStatus.PENDING)
                .build();
        Reservation saved = reservationRepository.save(reservation);

        eventServiceClient.updateSeatStatus(request.eventId(), request.seatId(), SeatStatus.RESERVED);

        return ReservationResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public ReservationResponse getReservation(UUID reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found: " + reservationId));
        return ReservationResponse.from(reservation);
    }

    @Transactional(readOnly = true)
    public List<ReservationResponse> getReservations(String userId) {
        List<Reservation> reservations = userId != null
                ? reservationRepository.findByUserId(userId)
                : reservationRepository.findAll();

        return reservations.stream().map(ReservationResponse::from).collect(Collectors.toList());
    }

    @Transactional
    public ReservationResponse cancelReservation(UUID reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found: " + reservationId));

        reservation.setStatus(ReservationStatus.CANCELLED);
        Reservation saved = reservationRepository.save(reservation);

        eventServiceClient.updateSeatStatus(reservation.getEventId(), reservation.getSeatId(), SeatStatus.AVAILABLE);

        return ReservationResponse.from(saved);
    }
}
