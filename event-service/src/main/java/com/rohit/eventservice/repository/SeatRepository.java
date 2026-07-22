package com.rohit.eventservice.repository;

import com.rohit.eventservice.model.Seat;
import com.rohit.eventservice.model.SeatStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeatRepository extends JpaRepository<Seat, UUID> {

    List<Seat> findByEventId(UUID eventId);

    List<Seat> findByEventIdAndStatus(UUID eventId, SeatStatus status);

    Optional<Seat> findByIdAndEventId(UUID id, UUID eventId);
}
