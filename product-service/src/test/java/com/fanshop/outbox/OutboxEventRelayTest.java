package com.fanshop.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;

import com.fanshop.messaging.StockEventPublisher;
import com.fanshop.messaging.event.InventoryRejectedEvent;
import com.fanshop.messaging.event.InventoryReservedEvent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.BDDMockito;

import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class OutboxEventRelayTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private StockEventPublisher stockEventPublisher;

    @Spy
    private ObjectMapper objectMapper;

    @InjectMocks
    private OutboxEventRelay outboxEventRelay;

    /** 단위 테스트가 설정에 의존하지 않도록 배치 크기를 명시해 호출한다. 값 자체는 이 테스트의 관심사가 아니다. */
    private static final int BATCH = 100;

    @Test
    @DisplayName("INVENTORY_RESERVED를 발행하고 PUBLISHED로 마킹한다")
    void publishesReserved() {
        InventoryReservedEvent event = new InventoryReservedEvent(1L, 2L, 3L, 4, 50000L);
        OutboxEvent outboxEvent = new OutboxEvent("INVENTORY_RESERVED", objectMapper.writeValueAsString(event));
        BDDMockito.given(outboxEventRepository.findPendingBatch(BATCH)).willReturn(List.of(outboxEvent));

        outboxEventRelay.relayBatch(BATCH);

        verify(stockEventPublisher).publishInventoryReserved(event);
        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(outboxEvent.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("INVENTORY_REJECTED를 발행하고 PUBLISHED로 마킹한다")
    void publishesRejected() {
        InventoryRejectedEvent event = new InventoryRejectedEvent(1L, "재고 부족");
        OutboxEvent outboxEvent = new OutboxEvent("INVENTORY_REJECTED", objectMapper.writeValueAsString(event));
        BDDMockito.given(outboxEventRepository.findPendingBatch(BATCH)).willReturn(List.of(outboxEvent));

        outboxEventRelay.relayBatch(BATCH);

        verify(stockEventPublisher).publishInventoryRejected(event);
        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(outboxEvent.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("알 수 없는 이벤트 타입은 격리된다")
    void isolatesUnknownEventType() {
        OutboxEvent outboxEvent = new OutboxEvent("UNKNOWN_TYPE", "{}");
        BDDMockito.given(outboxEventRepository.findPendingBatch(BATCH)).willReturn(List.of(outboxEvent));

        outboxEventRelay.relayBatch(BATCH);

        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(outboxEvent.getRetryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("발행이 최대 재시도 횟수만큼 실패하면 FAILED로 격리된다")
    void isolatesAfterMaxAttempts() {
        InventoryReservedEvent event = new InventoryReservedEvent(1L, 2L, 3L, 4, 50000L);
        OutboxEvent outboxEvent = new OutboxEvent("INVENTORY_RESERVED", objectMapper.writeValueAsString(event));
        BDDMockito.given(outboxEventRepository.findPendingBatch(BATCH)).willReturn(List.of(outboxEvent));
        doThrow(new RuntimeException("Kafka 연결 실패")).when(stockEventPublisher).publishInventoryReserved(any());

        for (int i = 0; i < OutboxEventRelay.MAX_ATTEMPTS; i++) {
            outboxEventRelay.relayBatch(BATCH);
        }

        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
    }

    @Test
    @DisplayName("단일 발행 실패 후 행은 PENDING 상태를 유지한다")
    void keepsRowPendingAfterSingleFailure() {
        InventoryReservedEvent event = new InventoryReservedEvent(1L, 2L, 3L, 4, 50000L);
        OutboxEvent outboxEvent = new OutboxEvent("INVENTORY_RESERVED", objectMapper.writeValueAsString(event));
        BDDMockito.given(outboxEventRepository.findPendingBatch(BATCH)).willReturn(List.of(outboxEvent));
        doThrow(new RuntimeException("Kafka 연결 실패")).when(stockEventPublisher).publishInventoryReserved(any());

        outboxEventRelay.relayBatch(BATCH);

        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(outboxEvent.getRetryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("대기 중인 이벤트가 없으면 발행자를 호출하지 않는다")
    void doesNotInvokePublisherWhenNoPendingEvents() {
        BDDMockito.given(outboxEventRepository.findPendingBatch(BATCH)).willReturn(List.of());

        outboxEventRelay.relayBatch(BATCH);

        verify(stockEventPublisher, never()).publishInventoryReserved(any());
        verify(stockEventPublisher, never()).publishInventoryRejected(any());
    }

    @Test
    @DisplayName("연속 발행 실패가 임계치에 도달하면 나머지 배치는 시도하지 않고 이번 틱을 중단한다")
    void breaksAfterConsecutiveFailures() {
        List<OutboxEvent> batch = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            InventoryReservedEvent event = new InventoryReservedEvent((long) i, 2L, 3L, 4, 50000L);
            batch.add(new OutboxEvent("INVENTORY_RESERVED", objectMapper.writeValueAsString(event)));
        }
        BDDMockito.given(outboxEventRepository.findPendingBatch(BATCH)).willReturn(batch);
        doThrow(new RuntimeException("Kafka 연결 실패")).when(stockEventPublisher).publishInventoryReserved(any());

        outboxEventRelay.relayBatch(BATCH);

        verify(stockEventPublisher, times(OutboxEventRelay.MAX_CONSECUTIVE_FAILURES)).publishInventoryReserved(any());
        assertThat(batch.subList(0, OutboxEventRelay.MAX_CONSECUTIVE_FAILURES))
            .allSatisfy(e -> assertThat(e.getRetryCount()).isEqualTo(1));
        assertThat(batch.subList(OutboxEventRelay.MAX_CONSECUTIVE_FAILURES, 5))
            .allSatisfy(e -> assertThat(e.getRetryCount()).isZero());
    }

}
