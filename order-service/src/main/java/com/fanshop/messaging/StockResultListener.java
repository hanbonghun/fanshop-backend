package com.fanshop.messaging;

import java.util.function.Consumer;

import com.fanshop.common.idempotency.ProcessedEvent;
import com.fanshop.common.idempotency.ProcessedEventRepository;
import com.fanshop.messaging.event.InventoryRejectedEvent;
import com.fanshop.messaging.event.InventoryReservedEvent;
import com.fanshop.messaging.event.PaymentCompletedEvent;
import com.fanshop.messaging.event.PaymentFailedEvent;
import com.fanshop.order.service.OrderService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class StockResultListener {

    private static final String INVENTORY_RESERVED = "INVENTORY_RESERVED";

    private static final String INVENTORY_REJECTED = "INVENTORY_REJECTED";

    private static final String PAYMENT_COMPLETED = "PAYMENT_COMPLETED";

    private static final String PAYMENT_FAILED = "PAYMENT_FAILED";

    private final OrderService orderService;

    private final ProcessedEventRepository processedEventRepository;

    @Bean
    public Consumer<InventoryReservedEvent> inventoryReservedConsumer() {
        return this::handleInventoryReserved;
    }

    @Bean
    public Consumer<InventoryRejectedEvent> inventoryRejectedConsumer() {
        return this::handleInventoryRejected;
    }

    @Bean
    public Consumer<PaymentCompletedEvent> paymentCompletedConsumer() {
        return this::handlePaymentCompleted;
    }

    @Bean
    public Consumer<PaymentFailedEvent> paymentFailedConsumer() {
        return this::handlePaymentFailed;
    }

    public void handleInventoryReserved(InventoryReservedEvent event) {
        String eventId = String.valueOf(event.orderId());
        if (processedEventRepository.existsByEventIdAndEventType(eventId, INVENTORY_RESERVED)) {
            log.warn("중복 이벤트 무시 — type={}, orderId={}", INVENTORY_RESERVED, event.orderId());
            return;
        }
        processedEventRepository.save(new ProcessedEvent(eventId, INVENTORY_RESERVED));
        log.info("Received inventory.reserved — orderId={}", event.orderId());
        orderService.waitForPayment(event.orderId());
    }

    public void handleInventoryRejected(InventoryRejectedEvent event) {
        String eventId = String.valueOf(event.orderId());
        if (processedEventRepository.existsByEventIdAndEventType(eventId, INVENTORY_REJECTED)) {
            log.warn("중복 이벤트 무시 — type={}, orderId={}", INVENTORY_REJECTED, event.orderId());
            return;
        }
        processedEventRepository.save(new ProcessedEvent(eventId, INVENTORY_REJECTED));
        log.info("Received inventory.rejected — orderId={}, reason={}", event.orderId(), event.reason());
        orderService.cancelOrder(event.orderId(), event.reason());
    }

    public void handlePaymentCompleted(PaymentCompletedEvent event) {
        String eventId = String.valueOf(event.orderId());
        if (processedEventRepository.existsByEventIdAndEventType(eventId, PAYMENT_COMPLETED)) {
            log.warn("중복 이벤트 무시 — type={}, orderId={}", PAYMENT_COMPLETED, event.orderId());
            return;
        }
        processedEventRepository.save(new ProcessedEvent(eventId, PAYMENT_COMPLETED));
        log.info("Received payment.completed — orderId={}", event.orderId());
        orderService.confirmOrder(event.orderId());
    }

    public void handlePaymentFailed(PaymentFailedEvent event) {
        String eventId = String.valueOf(event.orderId());
        if (processedEventRepository.existsByEventIdAndEventType(eventId, PAYMENT_FAILED)) {
            log.warn("중복 이벤트 무시 — type={}, orderId={}", PAYMENT_FAILED, event.orderId());
            return;
        }
        processedEventRepository.save(new ProcessedEvent(eventId, PAYMENT_FAILED));
        log.info("Received payment.failed — orderId={}, reason={}", event.orderId(), event.reason());
        orderService.cancelOrder(event.orderId(), event.reason());
    }

}
