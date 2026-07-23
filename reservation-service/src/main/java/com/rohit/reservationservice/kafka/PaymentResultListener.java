package com.rohit.reservationservice.kafka;

import com.rohit.reservationservice.event.PaymentConfirmedEvent;
import com.rohit.reservationservice.event.PaymentFailedEvent;
import com.rohit.reservationservice.service.ReservationService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentResultListener {

    private final ReservationService reservationService;

    public PaymentResultListener(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @KafkaListener(topics = "payment-confirmed")
    public void onPaymentConfirmed(PaymentConfirmedEvent event) {
        reservationService.confirmReservation(event.reservationId());
    }

    @KafkaListener(topics = "payment-failed")
    public void onPaymentFailed(PaymentFailedEvent event) {
        reservationService.failReservation(event.reservationId(), event.reason());
    }
}
