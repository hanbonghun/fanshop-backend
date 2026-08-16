package com.fanshop.messaging;

import java.util.function.Consumer;

import com.fanshop.messaging.event.OrderExpiredEvent;
import com.fanshop.messaging.event.PaymentCompletedEvent;
import com.fanshop.messaging.event.PaymentFailedEvent;

import lombok.RequiredArgsConstructor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class PaymentResultListener {

    private final PaymentResultHandler paymentResultHandler;

    private final OrderExpiredHandler orderExpiredHandler;

    @Bean
    public Consumer<PaymentCompletedEvent> paymentCompletedConsumer() {
        return paymentResultHandler::handlePaymentCompleted;
    }

    @Bean
    public Consumer<PaymentFailedEvent> paymentFailedConsumer() {
        return paymentResultHandler::handlePaymentFailed;
    }

    @Bean
    public Consumer<OrderExpiredEvent> orderExpiredConsumer() {
        return orderExpiredHandler::handleOrderExpired;
    }

}
