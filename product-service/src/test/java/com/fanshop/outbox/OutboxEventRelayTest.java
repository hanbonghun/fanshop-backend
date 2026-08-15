package com.fanshop.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.util.List;

import com.fanshop.messaging.StockEventPublisher;
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

    @Test
    @DisplayName("INVENTORY_RESERVED를 발행하고 PUBLISHED로 마킹한다")
    void publishesReserved() {
        InventoryReservedEvent event = new InventoryReservedEvent(1L, 2L, 3L, 4, 50000L);
        OutboxEvent outboxEvent = new OutboxEvent("INVENTORY_RESERVED", objectMapper.writeValueAsString(event));
        BDDMockito.given(outboxEventRepository.findPendingBatch(OutboxEventRelay.BATCH_SIZE))
            .willReturn(List.of(outboxEvent));

        outboxEventRelay.relay();

        verify(stockEventPublisher).publishInventoryReserved(event);
        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
    }

    @Test
    @DisplayName("발행이 최대 재시도 횟수만큼 실패하면 FAILED로 격리된다")
    void isolatesAfterMaxAttempts() {
        InventoryReservedEvent event = new InventoryReservedEvent(1L, 2L, 3L, 4, 50000L);
        OutboxEvent outboxEvent = new OutboxEvent("INVENTORY_RESERVED", objectMapper.writeValueAsString(event));
        BDDMockito.given(outboxEventRepository.findPendingBatch(OutboxEventRelay.BATCH_SIZE))
            .willReturn(List.of(outboxEvent));
        doThrow(new RuntimeException("Kafka 연결 실패")).when(stockEventPublisher).publishInventoryReserved(any());

        for (int i = 0; i < OutboxEventRelay.MAX_ATTEMPTS; i++) {
            outboxEventRelay.relay();
        }

        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
    }

}
