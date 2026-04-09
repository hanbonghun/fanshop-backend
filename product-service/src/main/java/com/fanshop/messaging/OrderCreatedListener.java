package com.fanshop.messaging;

import com.fanshop.messaging.event.OrderCreatedEvent;
import com.fanshop.messaging.event.StockResultEvent;
import com.fanshop.product.service.ProductService;
import com.fanshop.support.error.CoreException;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class OrderCreatedListener {

    private final ProductService productService;

    private final StockEventPublisher stockEventPublisher;

    @Bean
    public Consumer<OrderCreatedEvent> orderCreatedConsumer() {
        return this::handleOrderCreated;
    }

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
