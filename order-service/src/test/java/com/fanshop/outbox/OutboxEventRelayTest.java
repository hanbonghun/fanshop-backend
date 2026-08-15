package com.fanshop.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import com.fanshop.messaging.OrderEventPublisher;
import com.fanshop.messaging.event.OrderCreatedEvent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class OutboxEventRelayTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private OrderEventPublisher orderEventPublisher;

    @Spy
    private ObjectMapper objectMapper;

    @InjectMocks
    private OutboxEventRelay outboxEventRelay;

    @Nested
    @DisplayName("relay")
    class Relay {

        @Test
        @DisplayName("PENDING 이벤트를 Kafka로 발행하고 PUBLISHED로 마킹한다")
        void publishPendingEvents() throws Exception {
            // given
            OrderCreatedEvent event = new OrderCreatedEvent(1L, 2L, 3L, 1, 50000L);
            String payload = objectMapper.writeValueAsString(event);
            OutboxEvent outboxEvent = new OutboxEvent("ORDER_CREATED", payload);

            given(outboxEventRepository.findPendingBatch(OutboxEventRelay.BATCH_SIZE)).willReturn(List.of(outboxEvent));

            // when
            outboxEventRelay.relay();

            // then
            verify(orderEventPublisher).publishOrderCreated(any(OrderCreatedEvent.class));
            assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
            assertThat(outboxEvent.getPublishedAt()).isNotNull();
        }

        @Test
        @DisplayName("PENDING 이벤트가 없으면 Kafka 발행을 하지 않는다")
        void noPendingEvents() {
            // given
            given(outboxEventRepository.findPendingBatch(OutboxEventRelay.BATCH_SIZE)).willReturn(List.of());

            // when
            outboxEventRelay.relay();

            // then
            verify(orderEventPublisher, never()).publishOrderCreated(any());
        }

        @Test
        @DisplayName("Kafka 발행 실패 시 재시도 횟수를 기록하고 PENDING 상태로 유지된다")
        void publishFails() throws Exception {
            // given
            OrderCreatedEvent event = new OrderCreatedEvent(1L, 2L, 3L, 1, 50000L);
            String payload = objectMapper.writeValueAsString(event);
            OutboxEvent outboxEvent = new OutboxEvent("ORDER_CREATED", payload);

            given(outboxEventRepository.findPendingBatch(OutboxEventRelay.BATCH_SIZE)).willReturn(List.of(outboxEvent));
            doThrow(new RuntimeException("Kafka 연결 실패")).when(orderEventPublisher).publishOrderCreated(any());

            // when
            outboxEventRelay.relay();

            // then
            assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
            assertThat(outboxEvent.getRetryCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("최대 재시도 횟수 도달 시 FAILED로 격리되어 다음 폴링 대상에서 제외된다")
        void isolateAfterMaxAttempts() throws Exception {
            // given
            OrderCreatedEvent event = new OrderCreatedEvent(1L, 2L, 3L, 1, 50000L);
            String payload = objectMapper.writeValueAsString(event);
            OutboxEvent outboxEvent = new OutboxEvent("ORDER_CREATED", payload);

            given(outboxEventRepository.findPendingBatch(OutboxEventRelay.BATCH_SIZE)).willReturn(List.of(outboxEvent));
            doThrow(new RuntimeException("Kafka 연결 실패")).when(orderEventPublisher).publishOrderCreated(any());

            // when
            for (int i = 0; i < OutboxEventRelay.MAX_ATTEMPTS; i++) {
                outboxEventRelay.relay();
            }

            // then
            assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
            assertThat(outboxEvent.getRetryCount()).isEqualTo(OutboxEventRelay.MAX_ATTEMPTS);
        }

        @Test
        @DisplayName("알 수 없는 이벤트 타입은 PUBLISHED로 기록되지 않고 격리 경로를 탄다")
        void unknownEventTypeIsNotPublished() {
            // given
            OutboxEvent outboxEvent = new OutboxEvent("UNKNOWN_TYPE", "{}");
            given(outboxEventRepository.findPendingBatch(OutboxEventRelay.BATCH_SIZE)).willReturn(List.of(outboxEvent));

            // when
            outboxEventRelay.relay();

            // then
            assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
            assertThat(outboxEvent.getRetryCount()).isEqualTo(1);
            verify(orderEventPublisher, never()).publishOrderCreated(any());
        }

    }

}
