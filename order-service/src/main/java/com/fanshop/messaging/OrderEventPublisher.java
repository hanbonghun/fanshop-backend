package com.fanshop.messaging;

import com.fanshop.messaging.event.OrderCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventPublisher {

    private static final String ORDER_CREATED_BINDING = "orderCreated-out-0";

    private final StreamBridge streamBridge;

    public void publishOrderCreated(OrderCreatedEvent event) {
        streamBridge.send(ORDER_CREATED_BINDING, event);
        log.info("Published order.created: orderId={}", event.orderId());
    }

}
