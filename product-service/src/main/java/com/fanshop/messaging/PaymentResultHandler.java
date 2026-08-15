package com.fanshop.messaging;

import com.fanshop.common.idempotency.ProcessedEvent;
import com.fanshop.common.idempotency.ProcessedEventRepository;
import com.fanshop.messaging.event.PaymentCompletedEvent;
import com.fanshop.messaging.event.PaymentFailedEvent;
import com.fanshop.product.service.ProductService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 멱등성 기록과 재고 확정/해제를 한 트랜잭션으로 묶는다. 이전에는 멱등성 기록이 먼저 커밋되어, 재고 해제가 일시 실패하면 재시도가 스킵되고
 * reservedQuantity가 영구히 잠겼다. 이벤트를 발행하지 않으므로 Outbox는 쓰지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentResultHandler {

    static final String PAYMENT_COMPLETED = "PAYMENT_COMPLETED";

    static final String PAYMENT_FAILED = "PAYMENT_FAILED";

    private final ProductService productService;

    private final ProcessedEventRepository processedEventRepository;

    @Transactional
    public void handlePaymentCompleted(PaymentCompletedEvent event) {
        if (alreadyProcessed(event.orderId(), PAYMENT_COMPLETED)) {
            return;
        }
        log.info("Received payment.completed — orderId={}, productId={}", event.orderId(), event.productId());
        productService.confirmReservation(event.productId(), event.quantity());
    }

    @Transactional
    public void handlePaymentFailed(PaymentFailedEvent event) {
        if (alreadyProcessed(event.orderId(), PAYMENT_FAILED)) {
            return;
        }
        log.info("Received payment.failed — orderId={}, productId={}", event.orderId(), event.productId());
        productService.releaseReservation(event.productId(), event.quantity());
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
