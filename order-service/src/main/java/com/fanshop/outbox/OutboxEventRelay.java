package com.fanshop.outbox;

import java.util.List;

import com.fanshop.messaging.OrderEventPublisher;
import com.fanshop.messaging.event.OrderCreatedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventRelay {

    private final OutboxEventRepository outboxEventRepository;

    private final OrderEventPublisher orderEventPublisher;

    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void relay() {
        List<OutboxEvent> pending = outboxEventRepository
            .findByStatusOrderByCreatedAtAsc(OutboxEventStatus.PENDING);

        for (OutboxEvent outboxEvent : pending) {
            try {
                publish(outboxEvent);
                outboxEvent.markPublished();
            }
            catch (Exception e) {
                log.error("Outbox 이벤트 발행 실패 — id={}, type={}", outboxEvent.getId(),
                        outboxEvent.getEventType(), e);
            }
        }
    }

    private void publish(OutboxEvent outboxEvent) {
        if ("ORDER_CREATED".equals(outboxEvent.getEventType())) {
            OrderCreatedEvent event = deserialize(outboxEvent.getPayload(), OrderCreatedEvent.class);
            orderEventPublisher.publishOrderCreated(event);
            log.info("Outbox 이벤트 발행 완료 — type=ORDER_CREATED, orderId={}", event.orderId());
        }
        else {
            log.warn("알 수 없는 이벤트 타입 — type={}", outboxEvent.getEventType());
        }
    }

    private <T> T deserialize(String payload, Class<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        }
        catch (Exception e) {
            throw new RuntimeException("Outbox 이벤트 역직렬화 실패 — type=" + type.getSimpleName(), e);
        }
    }

}
