package com.fanshop.kafka;

import com.fanshop.kafka.event.OrderCreatedEvent;
import com.fanshop.kafka.event.StockResultEvent;
import com.fanshop.product.service.ProductService;
import com.fanshop.support.error.CoreException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCreatedListener {

    private final ProductService productService;

    private final StockEventPublisher stockEventPublisher;

    @KafkaListener(topics = "order.created", groupId = "product-service")
    public void handleOrderCreated(OrderCreatedEvent event) {
        log.info("Received order.created: orderId={}, productId={}", event.orderId(), event.productId());
        try {
            productService.decreaseStock(event.productId(), event.quantity());
            stockEventPublisher.publishStockResult(new StockResultEvent(event.orderId(), true, null));
        }
        catch (CoreException e) {
            log.warn("Stock decrease failed: orderId={}, reason={}", event.orderId(), e.getMessage());
            stockEventPublisher.publishStockResult(new StockResultEvent(event.orderId(), false, e.getMessage()));
        }
    }

}
