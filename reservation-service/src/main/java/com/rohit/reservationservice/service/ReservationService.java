package com.rohit.reservationservice.service;

import com.rohit.reservationservice.client.EventServiceClient;
import com.rohit.reservationservice.dto.CreateReservationRequest;
import com.rohit.reservationservice.dto.ReservationResponse;
import com.rohit.reservationservice.dto.SeatDto;
import com.rohit.reservationservice.exception.ResourceNotFoundException;
import com.rohit.reservationservice.exception.SeatLockedException;
import com.rohit.reservationservice.exception.SeatUnavailableException;
import com.rohit.reservationservice.kafka.ReservationEventPublisher;
import com.rohit.reservationservice.lock.SeatLockService;
import com.rohit.reservationservice.model.Reservation;
import com.rohit.reservationservice.model.ReservationStatus;
import com.rohit.reservationservice.model.SeatStatus;
import com.rohit.reservationservice.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final EventServiceClient eventServiceClient;
    private final SeatLockService seatLockService;
    private final ReservationEventPublisher reservationEventPublisher;

    // The Redis lock wraps the whole check-then-act against Event Service:
    // a concurrent request for the same seat now fails fast with
    // SeatLockedException instead of racing this one between the
    // availability check and the status update. Step 2 left this race open
    // on purpose; this closes it.
    @Transactional
    public ReservationResponse createReservation(CreateReservationRequest request) {
        if (!seatLockService.tryLock(request.seatId(), request.userId())) {
            throw new SeatLockedException("Seat " + request.seatId() + " is currently being reserved by another request");
        }
        try {
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
            reservationEventPublisher.publishSeatReserved(saved);

            return ReservationResponse.from(saved);
        } finally {
            seatLockService.unlock(request.seatId(), request.userId());
        }
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

    // Invoked by the Kafka listener on a payment-confirmed event. Kafka is
    // at-least-once delivery, so redelivery of the same event is expected —
    // only act on a reservation that's still PENDING, and treat anything
    // else as an already-applied duplicate rather than an error.
    @Transactional
    public void confirmReservation(UUID reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found: " + reservationId));

        if (reservation.getStatus() != ReservationStatus.PENDING) {
            log.info("Ignoring payment-confirmed for reservation {} in status {} (already processed)",
                    reservationId, reservation.getStatus());
            return;
        }

        if (!seatLockService.tryLock(reservation.getSeatId(), reservation.getUserId())) {
            throw new SeatLockedException("Seat " + reservation.getSeatId() + " is currently being reserved by another request");
        }
        try {
            reservation.setStatus(ReservationStatus.CONFIRMED);
            reservationRepository.save(reservation);

            eventServiceClient.updateSeatStatus(reservation.getEventId(), reservation.getSeatId(), SeatStatus.SOLD);
        } finally {
            seatLockService.unlock(reservation.getSeatId(), reservation.getUserId());
        }
    }

    // Invoked by the Kafka listener on a payment-failed event — same
    // idempotency guard as confirmReservation, same reasoning.
    @Transactional
    public void failReservation(UUID reservationId, String reason) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found: " + reservationId));

        if (reservation.getStatus() != ReservationStatus.PENDING) {
            log.info("Ignoring payment-failed for reservation {} in status {} (already processed)",
                    reservationId, reservation.getStatus());
            return;
        }

        if (!seatLockService.tryLock(reservation.getSeatId(), reservation.getUserId())) {
            throw new SeatLockedException("Seat " + reservation.getSeatId() + " is currently being reserved by another request");
        }
        try {
            reservation.setStatus(ReservationStatus.CANCELLED);
            reservation.setFailureReason(reason);
            reservationRepository.save(reservation);

            eventServiceClient.updateSeatStatus(reservation.getEventId(), reservation.getSeatId(), SeatStatus.AVAILABLE);
        } finally {
            seatLockService.unlock(reservation.getSeatId(), reservation.getUserId());
        }
    }

    @Transactional
    public ReservationResponse cancelReservation(UUID reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found: " + reservationId));

        if (!seatLockService.tryLock(reservation.getSeatId(), reservation.getUserId())) {
            throw new SeatLockedException("Seat " + reservation.getSeatId() + " is currently being reserved by another request");
        }
        try {
            reservation.setStatus(ReservationStatus.CANCELLED);
            Reservation saved = reservationRepository.save(reservation);

            eventServiceClient.updateSeatStatus(reservation.getEventId(), reservation.getSeatId(), SeatStatus.AVAILABLE);

            return ReservationResponse.from(saved);
        } finally {
            seatLockService.unlock(reservation.getSeatId(), reservation.getUserId());
        }
    }
}
