package com.rohit.paymentservice.kafka;

import com.rohit.paymentservice.event.SeatReservedEvent;
import com.rohit.paymentservice.service.PaymentService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class SeatReservedListener {

    private final PaymentService paymentService;

    public SeatReservedListener(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @KafkaListener(topics = "seat-reserved")
    public void onSeatReserved(SeatReservedEvent event) {
        paymentService.processSeatReserved(event);
    }
}
