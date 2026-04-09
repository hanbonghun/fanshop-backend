package com.fanshop.messaging;

import com.fanshop.messaging.event.PaymentCompletedEvent;
import com.fanshop.messaging.event.PaymentFailedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventPublisher {

    private static final String PAYMENT_COMPLETED_BINDING = "paymentCompleted-out-0";

    private static final String PAYMENT_FAILED_BINDING = "paymentFailed-out-0";

    private final StreamBridge streamBridge;

    public void publishPaymentCompleted(PaymentCompletedEvent event) {
        streamBridge.send(PAYMENT_COMPLETED_BINDING, event);
        log.info("Published payment.completed — orderId={}", event.orderId());
    }

    public void publishPaymentFailed(PaymentFailedEvent event) {
        streamBridge.send(PAYMENT_FAILED_BINDING, event);
        log.info("Published payment.failed — orderId={}, reason={}", event.orderId(), event.reason());
    }

}
