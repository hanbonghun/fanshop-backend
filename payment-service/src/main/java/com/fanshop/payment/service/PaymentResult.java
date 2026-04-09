package com.fanshop.payment.service;

import com.fanshop.messaging.event.PaymentCompletedEvent;
import com.fanshop.messaging.event.PaymentFailedEvent;

public sealed interface PaymentResult
        permits PaymentResult.Approved, PaymentResult.Failed, PaymentResult.AlreadyProcessed {

    record Approved(PaymentCompletedEvent event) implements PaymentResult {
    }

    record Failed(PaymentFailedEvent event) implements PaymentResult {
    }

    record AlreadyProcessed() implements PaymentResult {
    }

    static PaymentResult approved(PaymentCompletedEvent event) {
        return new Approved(event);
    }

    static PaymentResult failed(PaymentFailedEvent event) {
        return new Failed(event);
    }

    static PaymentResult alreadyProcessed() {
        return new AlreadyProcessed();
    }

}
