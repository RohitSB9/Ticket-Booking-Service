package com.rohit.paymentservice.gateway;

public record PaymentOutcome(boolean confirmed, String failureReason) {
    public static PaymentOutcome success() {
        return new PaymentOutcome(true, null);
    }

    public static PaymentOutcome failure(String reason) {
        return new PaymentOutcome(false, reason);
    }
}
