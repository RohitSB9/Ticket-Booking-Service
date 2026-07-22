package com.rohit.reservationservice.exception;

public class SeatLockedException extends RuntimeException {
    public SeatLockedException(String message) {
        super(message);
    }
}
