package com.fanshop.outbox;

import java.time.LocalDateTime;
import java.util.List;

import com.fanshop.messaging.StockEventPublisher;
import com.fanshop.messaging.event.InventoryRejectedEvent;
import com.fanshop.messaging.event.InventoryReservedEvent;

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

    static final int MAX_ATTEMPTS = 5;

    static final int BATCH_SIZE = 100;

    static final int RETENTION_DAYS = 7;

    private final OutboxEventRepository outboxEventRepository;

    private final StockEventPublisher stockEventPublisher;

    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelayString = "${outbox.relay.fixed-delay:1000}",
            initialDelayString = "${outbox.relay.initial-delay:0}")
    @Transactional
    public void relay() {
        for (OutboxEvent outboxEvent : outboxEventRepository.findPendingBatch(BATCH_SIZE)) {
            try {
                publish(outboxEvent);
                outboxEvent.markPublished();
            }
            catch (Exception e) {
                outboxEvent.recordFailure(MAX_ATTEMPTS);
                if (outboxEvent.getStatus() == OutboxEventStatus.FAILED) {
                    log.error("Outbox 이벤트 격리(FAILED) — 수동 복구 필요, id={}, type={}, retryCount={}", outboxEvent.getId(),
                            outboxEvent.getEventType(), outboxEvent.getRetryCount(), e);
                }
                else {
                    log.error("Outbox 이벤트 발행 실패 — id={}, type={}, retryCount={}/{}", outboxEvent.getId(),
                            outboxEvent.getEventType(), outboxEvent.getRetryCount(), MAX_ATTEMPTS, e);
                }
            }
        }
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void purgePublished() {
        purgeBefore(LocalDateTime.now().minusDays(RETENTION_DAYS));
    }

    @Transactional
    public int purgeBefore(LocalDateTime threshold) {
        int deleted = outboxEventRepository.deleteByStatusAndPublishedAtBefore(OutboxEventStatus.PUBLISHED, threshold);
        if (deleted > 0) {
            log.info("Outbox 정리 — PUBLISHED {}건 삭제 (기준 {})", deleted, threshold);
        }
        return deleted;
    }

    private void publish(OutboxEvent outboxEvent) {
        switch (outboxEvent.getEventType()) {
            case "INVENTORY_RESERVED" -> stockEventPublisher
                .publishInventoryReserved(deserialize(outboxEvent.getPayload(), InventoryReservedEvent.class));
            case "INVENTORY_REJECTED" -> stockEventPublisher
                .publishInventoryRejected(deserialize(outboxEvent.getPayload(), InventoryRejectedEvent.class));
            // 로그만 남기고 정상 반환하면 호출부에서 markPublished()가 실행되어, Kafka에 전달되지 않은 행이
            // PUBLISHED로 기록된다. 예외로 던져 recordFailure → FAILED 격리 경로를 타게 한다.
            default -> throw new IllegalStateException("알 수 없는 이벤트 타입 — type=" + outboxEvent.getEventType());
        }
    }

    private <T> T deserialize(String payload, Class<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        }
        catch (Exception e) {
            throw new IllegalStateException("Outbox 이벤트 역직렬화 실패 — type=" + type.getSimpleName(), e);
        }
    }

}
