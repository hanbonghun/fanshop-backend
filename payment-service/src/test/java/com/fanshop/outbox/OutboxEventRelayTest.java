package com.fanshop.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import com.fanshop.messaging.PaymentEventPublisher;
import com.fanshop.messaging.event.PaymentCompletedEvent;
import com.fanshop.messaging.event.PaymentFailedEvent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.BDDMockito;
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
    private PaymentEventPublisher paymentEventPublisher;

    @Spy
    private ObjectMapper objectMapper;

    @InjectMocks
    private OutboxEventRelay outboxEventRelay;

    /** 단위 테스트가 설정에 의존하지 않도록 배치 크기를 명시해 호출한다. 값 자체는 이 테스트의 관심사가 아니다. */
    private static final int BATCH = 100;


    @Test
    @DisplayName("PAYMENT_COMPLETED를 발행하고 PUBLISHED로 마킹한다")
    void publishesCompleted() {
        PaymentCompletedEvent event = new PaymentCompletedEvent(1L, 2L, 3L, 4);
        OutboxEvent outboxEvent = new OutboxEvent("PAYMENT_COMPLETED", objectMapper.writeValueAsString(event));
        BDDMockito.given(outboxEventRepository.findPendingBatch(BATCH))
            .willReturn(List.of(outboxEvent));

        outboxEventRelay.relayBatch(BATCH);

        verify(paymentEventPublisher).publishPaymentCompleted(event);
        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(outboxEvent.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("PAYMENT_FAILED를 발행하고 PUBLISHED로 마킹한다")
    void publishesFailed() {
        PaymentFailedEvent event = new PaymentFailedEvent(1L, 2L, 3L, 4, "잔액 부족");
        OutboxEvent outboxEvent = new OutboxEvent("PAYMENT_FAILED", objectMapper.writeValueAsString(event));
        BDDMockito.given(outboxEventRepository.findPendingBatch(BATCH))
            .willReturn(List.of(outboxEvent));

        outboxEventRelay.relayBatch(BATCH);

        verify(paymentEventPublisher).publishPaymentFailed(event);
        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(outboxEvent.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("알 수 없는 이벤트 타입은 격리된다")
    void isolatesUnknownEventType() {
        OutboxEvent outboxEvent = new OutboxEvent("UNKNOWN_TYPE", "{}");
        BDDMockito.given(outboxEventRepository.findPendingBatch(BATCH))
            .willReturn(List.of(outboxEvent));

        outboxEventRelay.relayBatch(BATCH);

        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(outboxEvent.getRetryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("발행이 최대 재시도 횟수만큼 실패하면 FAILED로 격리된다")
    void isolatesAfterMaxAttempts() {
        PaymentCompletedEvent event = new PaymentCompletedEvent(1L, 2L, 3L, 4);
        OutboxEvent outboxEvent = new OutboxEvent("PAYMENT_COMPLETED", objectMapper.writeValueAsString(event));
        BDDMockito.given(outboxEventRepository.findPendingBatch(BATCH))
            .willReturn(List.of(outboxEvent));
        doThrow(new RuntimeException("Kafka 연결 실패")).when(paymentEventPublisher).publishPaymentCompleted(any());

        for (int i = 0; i < OutboxEventRelay.MAX_ATTEMPTS; i++) {
            outboxEventRelay.relayBatch(BATCH);
        }

        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
    }

    @Test
    @DisplayName("단일 발행 실패 후 행은 PENDING 상태를 유지한다")
    void keepsRowPendingAfterSingleFailure() {
        PaymentCompletedEvent event = new PaymentCompletedEvent(1L, 2L, 3L, 4);
        OutboxEvent outboxEvent = new OutboxEvent("PAYMENT_COMPLETED", objectMapper.writeValueAsString(event));
        BDDMockito.given(outboxEventRepository.findPendingBatch(BATCH))
            .willReturn(List.of(outboxEvent));
        doThrow(new RuntimeException("Kafka 연결 실패")).when(paymentEventPublisher).publishPaymentCompleted(any());

        outboxEventRelay.relayBatch(BATCH);

        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(outboxEvent.getRetryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("대기 중인 이벤트가 없으면 발행자를 호출하지 않는다")
    void doesNotInvokePublisherWhenNoPendingEvents() {
        BDDMockito.given(outboxEventRepository.findPendingBatch(BATCH)).willReturn(List.of());

        outboxEventRelay.relayBatch(BATCH);

        verify(paymentEventPublisher, never()).publishPaymentCompleted(any());
        verify(paymentEventPublisher, never()).publishPaymentFailed(any());
    }

}
