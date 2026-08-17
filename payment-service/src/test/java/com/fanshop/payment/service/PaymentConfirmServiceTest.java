package com.fanshop.payment.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fanshop.messaging.event.PaymentCompletedEvent;
import com.fanshop.messaging.event.PaymentFailedEvent;
import com.fanshop.outbox.OutboxRecorder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentConfirmServiceTest {

    @Mock
    private PaymentService paymentService;

    @Mock
    private OutboxRecorder outboxRecorder;

    @InjectMocks
    private PaymentConfirmService paymentConfirmService;

    @Nested
    @DisplayName("confirm")
    class Confirm {

        @Test
        @DisplayName("승인되면 PAYMENT_COMPLETED를 Outbox에 기록한다")
        void recordsCompletedOnApproval() {
            // given
            PaymentCompletedEvent completedEvent = new PaymentCompletedEvent(1L, 2L, 3L, 4);
            given(paymentService.confirm(1L, "pay_key_1", 50000L)).willReturn(PaymentResult.approved(completedEvent));

            // when
            paymentConfirmService.confirm(1L, "pay_key_1", 50000L);

            // then
            verify(outboxRecorder).record("PAYMENT_COMPLETED", completedEvent);
        }

        @Test
        @DisplayName("거절되면 PAYMENT_FAILED를 Outbox에 기록한다 — 주문 취소와 재고 해제로 이어진다")
        void recordsFailedOnRejection() {
            // given
            PaymentFailedEvent failedEvent = new PaymentFailedEvent(1L, 2L, 3L, 4, "잔액 부족");
            given(paymentService.confirm(1L, "pay_key_1", 50000L)).willReturn(PaymentResult.failed(failedEvent));

            // when
            paymentConfirmService.confirm(1L, "pay_key_1", 50000L);

            // then
            verify(outboxRecorder).record("PAYMENT_FAILED", failedEvent);
        }

        @Test
        @DisplayName("이미 처리된 결제면 Outbox에 다시 기록하지 않는다")
        void recordsNothingWhenAlreadyProcessed() {
            // given
            given(paymentService.confirm(1L, "pay_key_1", 50000L)).willReturn(PaymentResult.alreadyProcessed());

            // when
            paymentConfirmService.confirm(1L, "pay_key_1", 50000L);

            // then
            verify(outboxRecorder, never()).record(any(), any());
        }

    }

}
