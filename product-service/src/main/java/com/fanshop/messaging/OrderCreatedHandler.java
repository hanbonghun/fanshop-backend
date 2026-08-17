package com.fanshop.messaging;

import com.fanshop.common.idempotency.ProcessedEvent;
import com.fanshop.common.idempotency.ProcessedEventRepository;
import com.fanshop.messaging.event.InventoryRejectedEvent;
import com.fanshop.messaging.event.InventoryReservedEvent;
import com.fanshop.messaging.event.OrderCreatedEvent;
import com.fanshop.outbox.OutboxRecorder;
import com.fanshop.product.service.ProductService;
import com.fanshop.product.service.ReservationResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 멱등성 기록 · 재고 예약 · 발행 예약을 한 트랜잭션으로 묶는다. 셋이 함께 커밋되거나 함께 롤백되므로, 비즈니스 처리가 일시 실패하면 멱등성 기록도 남지
 * 않아 재시도가 정상 동작한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCreatedHandler {

    static final String EVENT_TYPE = "ORDER_CREATED";

    static final String INVENTORY_RESERVED = "INVENTORY_RESERVED";

    static final String INVENTORY_REJECTED = "INVENTORY_REJECTED";

    private final ProductService productService;

    private final ProcessedEventRepository processedEventRepository;

    private final OutboxRecorder outboxRecorder;

    @Transactional
    public void handle(OrderCreatedEvent event) {
        String eventId = String.valueOf(event.orderId());
        if (processedEventRepository.existsByEventIdAndEventType(eventId, EVENT_TYPE)) {
            log.warn("중복 이벤트 무시 — type={}, orderId={}", EVENT_TYPE, event.orderId());
            return;
        }
        processedEventRepository.save(new ProcessedEvent(eventId, EVENT_TYPE));

        log.info("Received order.created — orderId={}, productId={}", event.orderId(), event.productId());
        switch (productService.softReserveStock(event.orderId(), event.productId(), event.quantity())) {
            case ReservationResult.Reserved() ->
                outboxRecorder.record(INVENTORY_RESERVED, new InventoryReservedEvent(event.orderId(), event.memberId(),
                        event.productId(), event.quantity(), event.totalPrice()));
            case ReservationResult.Rejected(String reason) -> {
                log.warn("Inventory reservation rejected — orderId={}, reason={}", event.orderId(), reason);
                outboxRecorder.record(INVENTORY_REJECTED, new InventoryRejectedEvent(event.orderId(), reason));
            }
        }
    }

}
