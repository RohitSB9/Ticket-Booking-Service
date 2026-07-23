package com.rohit.paymentservice.controller;

import com.rohit.paymentservice.dto.PaymentResponse;
import com.rohit.paymentservice.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @GetMapping
    public ResponseEntity<List<PaymentResponse>> getPayments() {
        return ResponseEntity.ok(paymentService.getPayments());
    }

    @GetMapping("/{reservationId}")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable UUID reservationId) {
        return ResponseEntity.ok(paymentService.getPaymentByReservationId(reservationId));
    }
}
