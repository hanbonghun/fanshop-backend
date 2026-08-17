package com.fanshop.outbox;

import java.time.LocalDateTime;
import java.util.List;

import com.fanshop.messaging.OrderEventPublisher;
import com.fanshop.messaging.event.OrderCreatedEvent;
import com.fanshop.messaging.event.OrderExpiredEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventRelay {

    static final int MAX_ATTEMPTS = 5;


    static final int RETENTION_DAYS = 7;

    static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final OutboxEventRepository outboxEventRepository;

    private final OrderEventPublisher orderEventPublisher;

    private final ObjectMapper objectMapper;

    /**
     * 한 틱이 가져갈 이벤트 수. 릴레이의 처리량 상한은 {@code 배치 크기 / 폴링 주기}로 정해지므로 둘 다 조절할 수 있어야 어느 쪽이 실제
     * 제약인지 가릴 수 있다. 키우면 한 틱의 처리량은 늘지만 트랜잭션이 길어져 {@code FOR UPDATE SKIP LOCKED}로 잡은 행과 DB
     * 커넥션을 그만큼 오래 붙든다. 최적값은 환경마다 달라 측정으로 정할 값이다.
     */
    @Value("${outbox.relay.batch-size:100}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${outbox.relay.fixed-delay:1000}",
            initialDelayString = "${outbox.relay.initial-delay:0}")
    @Transactional
    public void relay() {
        relayBatch(batchSize);
    }

    @Transactional
    public void relayBatch(int batchSize) {
        List<OutboxEvent> pending = outboxEventRepository.findPendingBatch(batchSize);

        int consecutiveFailures = 0;
        for (OutboxEvent outboxEvent : pending) {
            try {
                publish(outboxEvent);
                outboxEvent.markPublished();
                consecutiveFailures = 0;
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

                // 브로커 장애는 배치의 나머지 항목도 같은 이유로 실패할 가능성이 높다. 계속 시도하면
                // DB 트랜잭션·Hikari 커넥션·SKIP LOCKED로 잡은 행 잠금을 발행 타임아웃만큼 오래 붙들고,
                // scheduling pool size가 기본 1이라 purgePublished까지 굶긴다. 연속 실패가 임계치를
                // 넘으면 이번 틱을 중단하고 나머지는 다음 폴링에서 재시도한다.
                consecutiveFailures++;
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    log.error("연속 발행 실패 {}건 — 브로커 장애로 판단해 이번 틱을 중단한다", consecutiveFailures);
                    break;
                }
            }
        }
    }

    private void publish(OutboxEvent outboxEvent) {
        if ("ORDER_EXPIRED".equals(outboxEvent.getEventType())) {
            orderEventPublisher.publishOrderExpired(deserialize(outboxEvent.getPayload(), OrderExpiredEvent.class));
        }
        else if ("ORDER_CREATED".equals(outboxEvent.getEventType())) {
            OrderCreatedEvent event = deserialize(outboxEvent.getPayload(), OrderCreatedEvent.class);
            orderEventPublisher.publishOrderCreated(event);
        }
        else {
            // 로그만 남기고 정상 반환하면 호출부에서 markPublished()가 실행되어, Kafka에 전달되지 않은 행이
            // PUBLISHED로 기록된다. 예외로 던져 recordFailure → FAILED 격리 경로를 타게 한다.
            throw new IllegalStateException("알 수 없는 이벤트 타입 — type=" + outboxEvent.getEventType());
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

    /**
     * 발행에 성공한 이벤트는 보관 기간이 지나면 삭제한다. FAILED는 수동 복구 대상이라 남긴다.
     */
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

}
