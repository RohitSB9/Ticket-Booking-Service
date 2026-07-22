package com.rohit.eventservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.UUID;

@Entity
@Table(
    name = "seats",
    uniqueConstraints = @UniqueConstraint(columnNames = {"event_id", "seat_label"})
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Seat {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    @ToString.Exclude
    private Event event;

    // e.g. "A1", "A2", "B1"
    @Column(name = "seat_label", nullable = false)
    private String seatLabel;

    @Column(nullable = false)
    private String section;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private SeatStatus status = SeatStatus.AVAILABLE;

    // Optimistic lock as a backstop in addition to the Redis lock
    // that the Reservation Service will use in step 3.
    @Version
    private Long version;
}
