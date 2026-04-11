package com.fanshop.messaging;

import java.util.function.Consumer;

import com.fanshop.common.idempotency.ProcessedEvent;
import com.fanshop.common.idempotency.ProcessedEventRepository;
import com.fanshop.messaging.event.InventoryRejectedEvent;
import com.fanshop.messaging.event.InventoryReservedEvent;
import com.fanshop.messaging.event.OrderCreatedEvent;
import com.fanshop.product.service.ProductService;
import com.fanshop.support.error.CoreException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class OrderCreatedListener {

    private static final String EVENT_TYPE = "ORDER_CREATED";

    private final ProductService productService;

    private final StockEventPublisher stockEventPublisher;

    private final ProcessedEventRepository processedEventRepository;

    @Bean
    public Consumer<OrderCreatedEvent> orderCreatedConsumer() {
        return this::handleOrderCreated;
    }

    public void handleOrderCreated(OrderCreatedEvent event) {
        String eventId = String.valueOf(event.orderId());
        if (processedEventRepository.existsByEventIdAndEventType(eventId, EVENT_TYPE)) {
            log.warn("중복 이벤트 무시 — type={}, orderId={}", EVENT_TYPE, event.orderId());
            return;
        }
        processedEventRepository.save(new ProcessedEvent(eventId, EVENT_TYPE));

        log.info("Received order.created — orderId={}, productId={}", event.orderId(), event.productId());
        try {
            productService.softReserveStock(event.productId(), event.quantity());
            stockEventPublisher.publishInventoryReserved(new InventoryReservedEvent(event.orderId(), event.memberId(),
                    event.productId(), event.quantity(), event.totalPrice()));
        }
        catch (CoreException e) {
            log.warn("Inventory reservation failed — orderId={}, reason={}", event.orderId(), e.getMessage());
            stockEventPublisher.publishInventoryRejected(new InventoryRejectedEvent(event.orderId(), e.getMessage()));
        }
    }

}
