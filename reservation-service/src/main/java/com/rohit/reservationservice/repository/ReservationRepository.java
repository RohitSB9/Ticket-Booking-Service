package com.rohit.reservationservice.repository;

import com.rohit.reservationservice.model.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    List<Reservation> findByUserId(String userId);
}
