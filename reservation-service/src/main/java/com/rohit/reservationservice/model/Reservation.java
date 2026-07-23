package com.rohit.reservationservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reservations", indexes = @Index(name = "idx_reservations_status_expires_at", columnList = "status, expires_at"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Reservation {

    @Id
    @GeneratedValue
    private UUID id;

    // References Event/Seat by id only — Reservation Service does not own
    // event or seat data, it calls Event Service for that.
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "seat_id", nullable = false)
    private UUID seatId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ReservationStatus status = ReservationStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    // Set when a payment-failed event or a user cancel puts the reservation
    // into CANCELLED; null otherwise.
    @Column(name = "failure_reason")
    private String failureReason;

    // How long a PENDING reservation is allowed to sit without a payment
    // outcome before the sweep expires it. Not consulted for CONFIRMED/
    // CANCELLED/EXPIRED reservations.
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
