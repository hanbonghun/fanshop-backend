package com.fanshop.messaging;

import com.fanshop.messaging.event.OrderCreatedEvent;
import com.fanshop.messaging.event.OrderExpiredEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventPublisher {

    private static final String ORDER_CREATED_BINDING = "orderCreated-out-0";

    private static final String ORDER_EXPIRED_BINDING = "orderExpired-out-0";

    private final StreamBridge streamBridge;

    public void publishOrderCreated(OrderCreatedEvent event) {
        if (!streamBridge.send(ORDER_CREATED_BINDING, event)) {
            throw new IllegalStateException("order.created 발행 실패 — orderId=" + event.orderId());
        }
        log.info("Published order.created: orderId={}", event.orderId());
    }

    public void publishOrderExpired(OrderExpiredEvent event) {
        if (!streamBridge.send(ORDER_EXPIRED_BINDING, event)) {
            throw new IllegalStateException("order.expired 발행 실패 — orderId=" + event.orderId());
        }
        log.info("Published order.expired: orderId={}", event.orderId());
    }

}
