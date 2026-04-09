package com.fanshop.messaging;

import java.util.function.Consumer;

import com.fanshop.messaging.event.PaymentCompletedEvent;
import com.fanshop.messaging.event.PaymentFailedEvent;
import com.fanshop.product.service.ProductService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class PaymentResultListener {

    private final ProductService productService;

    @Bean
    public Consumer<PaymentCompletedEvent> paymentCompletedConsumer() {
        return event -> {
            log.info("Received payment.completed — orderId={}, productId={}", event.orderId(), event.productId());
            productService.confirmReservation(event.productId(), event.quantity());
        };
    }

    @Bean
    public Consumer<PaymentFailedEvent> paymentFailedConsumer() {
        return event -> {
            log.info("Received payment.failed — orderId={}, productId={}", event.orderId(), event.productId());
            productService.releaseReservation(event.productId(), event.quantity());
        };
    }

}
