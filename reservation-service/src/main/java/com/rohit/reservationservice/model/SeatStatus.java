package com.rohit.reservationservice.model;

// Mirrors com.rohit.eventservice.model.SeatStatus. Duplicated rather than
// shared because each service owns its own contract with Event Service's
// JSON responses — no shared library between services in this project.
public enum SeatStatus {
    AVAILABLE,
    RESERVED,
    SOLD
}
