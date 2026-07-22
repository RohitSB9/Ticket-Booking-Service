package com.rohit.reservationservice.controller;

import com.rohit.reservationservice.dto.CreateReservationRequest;
import com.rohit.reservationservice.dto.ReservationResponse;
import com.rohit.reservationservice.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping
    public ResponseEntity<ReservationResponse> createReservation(@Valid @RequestBody CreateReservationRequest request) {
        ReservationResponse response = reservationService.createReservation(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<ReservationResponse>> getReservations(
            @RequestParam(required = false) String userId
    ) {
        return ResponseEntity.ok(reservationService.getReservations(userId));
    }

    @GetMapping("/{reservationId}")
    public ResponseEntity<ReservationResponse> getReservation(@PathVariable UUID reservationId) {
        return ResponseEntity.ok(reservationService.getReservation(reservationId));
    }

    @DeleteMapping("/{reservationId}")
    public ResponseEntity<ReservationResponse> cancelReservation(@PathVariable UUID reservationId) {
        return ResponseEntity.ok(reservationService.cancelReservation(reservationId));
    }
}
