package com.rohit.reservationservice.scheduling;

import com.rohit.reservationservice.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

// Safety net for abandoned reservations: normally Payment Service resolves
// a PENDING reservation within a few hundred ms of the seat-reserved event,
// but if that never happens (Payment Service down, message lost, a real
// gateway waiting on user input that never arrives), this sweep is what
// eventually frees the seat instead of it staying held forever.
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationExpirySweep {

    private final ReservationService reservationService;

    @Scheduled(fixedDelayString = "${reservation.expiry-sweep.fixed-delay-ms}")
    public void sweep() {
        for (UUID reservationId : reservationService.findExpiredPendingReservationIds()) {
            try {
                reservationService.expireReservation(reservationId);
            } catch (Exception e) {
                // One bad reservation shouldn't stop the rest of the sweep
                // from running; it'll be retried next tick regardless.
                log.warn("Failed to expire reservation {}, will retry next sweep", reservationId, e);
            }
        }
    }
}
