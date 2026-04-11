package com.fanshop.messaging;

import java.util.function.Consumer;

import com.fanshop.common.idempotency.ProcessedEvent;
import com.fanshop.common.idempotency.ProcessedEventRepository;
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

    private static final String PAYMENT_COMPLETED = "PAYMENT_COMPLETED";

    private static final String PAYMENT_FAILED = "PAYMENT_FAILED";

    private final ProductService productService;

    private final ProcessedEventRepository processedEventRepository;

    @Bean
    public Consumer<PaymentCompletedEvent> paymentCompletedConsumer() {
        return this::handlePaymentCompleted;
    }

    @Bean
    public Consumer<PaymentFailedEvent> paymentFailedConsumer() {
        return this::handlePaymentFailed;
    }

    public void handlePaymentCompleted(PaymentCompletedEvent event) {
        String eventId = String.valueOf(event.orderId());
        if (processedEventRepository.existsByEventIdAndEventType(eventId, PAYMENT_COMPLETED)) {
            log.warn("중복 이벤트 무시 — type={}, orderId={}", PAYMENT_COMPLETED, event.orderId());
            return;
        }
        processedEventRepository.save(new ProcessedEvent(eventId, PAYMENT_COMPLETED));

        log.info("Received payment.completed — orderId={}, productId={}", event.orderId(), event.productId());
        productService.confirmReservation(event.productId(), event.quantity());
    }

    public void handlePaymentFailed(PaymentFailedEvent event) {
        String eventId = String.valueOf(event.orderId());
        if (processedEventRepository.existsByEventIdAndEventType(eventId, PAYMENT_FAILED)) {
            log.warn("중복 이벤트 무시 — type={}, orderId={}", PAYMENT_FAILED, event.orderId());
            return;
        }
        processedEventRepository.save(new ProcessedEvent(eventId, PAYMENT_FAILED));

        log.info("Received payment.failed — orderId={}, productId={}", event.orderId(), event.productId());
        productService.releaseReservation(event.productId(), event.quantity());
    }

}
