package com.fanshop.messaging;

import java.util.function.Consumer;

import com.fanshop.messaging.event.InventoryReservedEvent;
import com.fanshop.payment.service.PaymentResult;
import com.fanshop.payment.service.PaymentService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class InventoryReservedListener {

    private final PaymentService paymentService;
    private final PaymentEventPublisher paymentEventPublisher;

    @Bean
    public Consumer<InventoryReservedEvent> inventoryReservedConsumer() {
        return this::handleInventoryReserved;
    }

    public void handleInventoryReserved(InventoryReservedEvent event) {
        log.info("Received inventory.reserved — orderId={}", event.orderId());
        PaymentResult result = paymentService.processPayment(event);

        switch (result) {
            case PaymentResult.Approved(var completedEvent) -> paymentEventPublisher.publishPaymentCompleted(completedEvent);
            case PaymentResult.Failed(var failedEvent) -> paymentEventPublisher.publishPaymentFailed(failedEvent);
            case PaymentResult.AlreadyProcessed() -> log.warn("이미 처리된 결제 — orderId={}", event.orderId());
        }
    }

}
