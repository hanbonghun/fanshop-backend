package com.fanshop.messaging;

import com.fanshop.common.idempotency.ProcessedEvent;
import com.fanshop.common.idempotency.ProcessedEventRepository;
import com.fanshop.messaging.event.InventoryRejectedEvent;
import com.fanshop.messaging.event.InventoryReservedEvent;
import com.fanshop.messaging.event.PaymentCompletedEvent;
import com.fanshop.messaging.event.PaymentFailedEvent;
import com.fanshop.order.service.OrderService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 멱등성 기록과 주문 상태 변경을 한 트랜잭션으로 묶는다. 이전에는 멱등성 기록이 먼저 커밋되어, 상태 변경이 일시 실패하면 재시도가 "이미 처리됨"으로
 * 판단해 조용히 스킵했다. 이벤트를 발행하지 않는 리스너이므로 Outbox는 쓰지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockResultHandler {

    static final String INVENTORY_RESERVED = "INVENTORY_RESERVED";

    static final String INVENTORY_REJECTED = "INVENTORY_REJECTED";

    static final String PAYMENT_COMPLETED = "PAYMENT_COMPLETED";

    static final String PAYMENT_FAILED = "PAYMENT_FAILED";

    private final OrderService orderService;

    private final ProcessedEventRepository processedEventRepository;

    @Transactional
    public void handleInventoryReserved(InventoryReservedEvent event) {
        if (alreadyProcessed(event.orderId(), INVENTORY_RESERVED)) {
            return;
        }
        log.info("Received inventory.reserved — orderId={}", event.orderId());
        orderService.waitForPayment(event.orderId());
    }

    @Transactional
    public void handleInventoryRejected(InventoryRejectedEvent event) {
        if (alreadyProcessed(event.orderId(), INVENTORY_REJECTED)) {
            return;
        }
        log.info("Received inventory.rejected — orderId={}, reason={}", event.orderId(), event.reason());
        orderService.cancelOrder(event.orderId(), event.reason());
    }

    @Transactional
    public void handlePaymentCompleted(PaymentCompletedEvent event) {
        if (alreadyProcessed(event.orderId(), PAYMENT_COMPLETED)) {
            return;
        }
        log.info("Received payment.completed — orderId={}", event.orderId());
        orderService.confirmOrder(event.orderId());
    }

    @Transactional
    public void handlePaymentFailed(PaymentFailedEvent event) {
        if (alreadyProcessed(event.orderId(), PAYMENT_FAILED)) {
            return;
        }
        log.info("Received payment.failed — orderId={}, reason={}", event.orderId(), event.reason());
        orderService.cancelOrder(event.orderId(), event.reason());
    }

    private boolean alreadyProcessed(Long orderId, String eventType) {
        String eventId = String.valueOf(orderId);
        if (processedEventRepository.existsByEventIdAndEventType(eventId, eventType)) {
            log.warn("중복 이벤트 무시 — type={}, orderId={}", eventType, orderId);
            return true;
        }
        processedEventRepository.save(new ProcessedEvent(eventId, eventType));
        return false;
    }

}
