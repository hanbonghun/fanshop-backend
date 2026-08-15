package com.fanshop.messaging;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.fanshop.messaging.event.InventoryReservedEvent;
import com.fanshop.messaging.event.PaymentCompletedEvent;
import com.fanshop.messaging.event.PaymentFailedEvent;
import com.fanshop.outbox.OutboxRecorder;
import com.fanshop.payment.service.PaymentResult;
import com.fanshop.payment.service.PaymentService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InventoryReservedHandlerTest {

    @Mock
    private PaymentService paymentService;

    @Mock
    private OutboxRecorder outboxRecorder;

    @InjectMocks
    private InventoryReservedHandler inventoryReservedHandler;

    private final InventoryReservedEvent event = new InventoryReservedEvent(1L, 2L, 3L, 1, 50000L);

    @Nested
    @DisplayName("handle")
    class Handle {

        @Test
        @DisplayName("결제 승인 시 PAYMENT_COMPLETED를 Outbox에 기록한다")
        void recordsCompletedOnApproval() {
            // given
            PaymentCompletedEvent completedEvent = new PaymentCompletedEvent(1L, 2L, 3L, 1);
            given(paymentService.processPayment(event)).willReturn(PaymentResult.approved(completedEvent));

            // when
            inventoryReservedHandler.handle(event);

            // then
            verify(outboxRecorder).record("PAYMENT_COMPLETED", completedEvent);
        }

        @Test
        @DisplayName("결제 실패 시 PAYMENT_FAILED를 Outbox에 기록한다")
        void recordsFailedOnRejection() {
            // given
            PaymentFailedEvent failedEvent = new PaymentFailedEvent(1L, 2L, 3L, 1, "잔액 부족");
            given(paymentService.processPayment(event)).willReturn(PaymentResult.failed(failedEvent));

            // when
            inventoryReservedHandler.handle(event);

            // then
            verify(outboxRecorder).record("PAYMENT_FAILED", failedEvent);
        }

    }

}
