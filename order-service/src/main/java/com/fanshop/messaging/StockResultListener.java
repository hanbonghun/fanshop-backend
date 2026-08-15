package com.fanshop.messaging;

import java.util.function.Consumer;

import com.fanshop.messaging.event.InventoryRejectedEvent;
import com.fanshop.messaging.event.InventoryReservedEvent;
import com.fanshop.messaging.event.PaymentCompletedEvent;
import com.fanshop.messaging.event.PaymentFailedEvent;

import lombok.RequiredArgsConstructor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class StockResultListener {

    private final StockResultHandler stockResultHandler;

    @Bean
    public Consumer<InventoryReservedEvent> inventoryReservedConsumer() {
        return stockResultHandler::handleInventoryReserved;
    }

    @Bean
    public Consumer<InventoryRejectedEvent> inventoryRejectedConsumer() {
        return stockResultHandler::handleInventoryRejected;
    }

    @Bean
    public Consumer<PaymentCompletedEvent> paymentCompletedConsumer() {
        return stockResultHandler::handlePaymentCompleted;
    }

    @Bean
    public Consumer<PaymentFailedEvent> paymentFailedConsumer() {
        return stockResultHandler::handlePaymentFailed;
    }

}
