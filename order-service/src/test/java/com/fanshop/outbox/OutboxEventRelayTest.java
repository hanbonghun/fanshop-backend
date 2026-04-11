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

            given(outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxEventStatus.PENDING))
                .willReturn(List.of(outboxEvent));

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
            given(outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxEventStatus.PENDING))
                .willReturn(List.of());

            // when
            outboxEventRelay.relay();

            // then
            verify(orderEventPublisher, never()).publishOrderCreated(any());
        }

        @Test
        @DisplayName("Kafka 발행 실패 시 이벤트가 PENDING 상태로 유지된다")
        void publishFails() throws Exception {
            // given
            OrderCreatedEvent event = new OrderCreatedEvent(1L, 2L, 3L, 1, 50000L);
            String payload = objectMapper.writeValueAsString(event);
            OutboxEvent outboxEvent = new OutboxEvent("ORDER_CREATED", payload);

            given(outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxEventStatus.PENDING))
                .willReturn(List.of(outboxEvent));
            doThrow(new RuntimeException("Kafka 연결 실패")).when(orderEventPublisher).publishOrderCreated(any());

            // when
            outboxEventRelay.relay();

            // then
            assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        }

    }

}
