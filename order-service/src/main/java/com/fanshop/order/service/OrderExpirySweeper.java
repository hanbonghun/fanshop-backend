package com.fanshop.order.service;

import java.time.LocalDateTime;
import java.util.List;

import com.fanshop.messaging.event.OrderExpiredEvent;
import com.fanshop.order.domain.Order;
import com.fanshop.order.domain.OrderRepository;
import com.fanshop.order.domain.OrderStatus;
import com.fanshop.outbox.OutboxRecorder;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 응답이 오지 않는 주문을 만료시킨다.
 *
 * <p>
 * Outbox·멱등성·DLQ는 모두 "온 메시지"를 다루는 장치다. 응답이 영영 오지 않는 경우 — payment-service가 죽거나 메시지가 DLQ로
 * 격리된 경우 — 는 아무것도 유실되지 않았는데도 주문이 WAITING_PAYMENT에 남고 예약 재고가 잠긴 채로 있다. 그것을 알아채는 유일한 주체가 이
 * 스위퍼다.
 *
 * <p>
 * PENDING은 대상이 아니다. 재고가 예약됐는지 order-service가 알 수 없어 안전한 해제가 불가능하고, 재고를 잠그지 않으므로 피해도 작다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExpirySweeper {

    static final String ORDER_EXPIRED = "ORDER_EXPIRED";

    private final OrderRepository orderRepository;

    private final OutboxRecorder outboxRecorder;

    /** 결제 응답을 이 시간 이상 기다린 주문을 만료시킨다. 너무 짧으면 정상 주문을 죽이고, 길면 재고가 그만큼 잠긴다. */
    @Value("${order.expiry.threshold-minutes:5}")
    private long thresholdMinutes;

    @Scheduled(fixedDelayString = "${order.expiry.fixed-delay:60000}",
            initialDelayString = "${order.expiry.initial-delay:60000}")
    @Transactional
    public void sweep() {
        expireBefore(LocalDateTime.now().minusMinutes(thresholdMinutes));
    }

    /**
     * 상태 전이와 해제 이벤트 기록을 한 트랜잭션으로 묶는다. 만료시켜놓고 이벤트를 못 남기면 재고가 영원히 잠기므로, 둘은 함께 커밋되거나 함께
     * 롤백되어야 한다.
     */
    @Transactional
    public int expireBefore(LocalDateTime threshold) {
        List<Order> stale = orderRepository.findByStatusAndUpdatedAtBefore(OrderStatus.WAITING_PAYMENT, threshold);

        for (Order order : stale) {
            order.expire();
            outboxRecorder.record(ORDER_EXPIRED,
                    new OrderExpiredEvent(order.getId(), order.getProductId(), order.getQuantity()));
            log.warn("주문 만료 — 결제 응답 없음, orderId={}, productId={}", order.getId(), order.getProductId());
        }
        return stale.size();
    }

}
