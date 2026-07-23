package com.rohit.paymentservice.gateway;

import org.springframework.stereotype.Component;

// Stands in for a real gateway (Stripe test mode, per the README) behind a
// narrow interface: one method, in, outcome out. Swapping this for a real
// Stripe PaymentIntent call later shouldn't require touching the Kafka
// listener or the rest of the service around it.
@Component
public class PaymentSimulator {

    // No real card data exists in this demo; a userId with this prefix is
    // the deterministic lever for exercising the failure path on demand,
    // instead of relying on randomness (which would make tests flaky).
    private static final String FORCED_FAILURE_PREFIX = "fail-";
    private static final String DECLINE_REASON = "Card declined (simulated)";

    public PaymentOutcome process(String userId) {
        simulateGatewayLatency();

        if (userId != null && userId.startsWith(FORCED_FAILURE_PREFIX)) {
            return PaymentOutcome.failure(DECLINE_REASON);
        }
        return PaymentOutcome.success();
    }

    private void simulateGatewayLatency() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
