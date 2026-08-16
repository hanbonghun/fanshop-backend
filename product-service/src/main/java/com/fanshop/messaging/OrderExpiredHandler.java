package com.fanshop.messaging;

import com.fanshop.common.idempotency.ProcessedEvent;
import com.fanshop.common.idempotency.ProcessedEventRepository;
import com.fanshop.messaging.event.OrderExpiredEvent;
import com.fanshop.product.service.ProductService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 만료를 받아 예약 재고를 해제한다. 멱등성 기록과 재고 해제를 한 트랜잭션으로 묶는다 — 기록만 남고 해제가 실패하면 재시도가 스킵되어 재고가 영구히
 * 잠긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExpiredHandler {

    static final String ORDER_EXPIRED = "ORDER_EXPIRED";

    private final ProductService productService;

    private final ProcessedEventRepository processedEventRepository;

    @Transactional
    public void handleOrderExpired(OrderExpiredEvent event) {
        String eventId = String.valueOf(event.orderId());
        if (processedEventRepository.existsByEventIdAndEventType(eventId, ORDER_EXPIRED)) {
            log.warn("중복 이벤트 무시 — type={}, orderId={}", ORDER_EXPIRED, event.orderId());
            return;
        }
        processedEventRepository.save(new ProcessedEvent(eventId, ORDER_EXPIRED));

        log.info("Received order.expired — orderId={}, productId={}", event.orderId(), event.productId());
        productService.releaseReservation(event.productId(), event.quantity());
    }

}
