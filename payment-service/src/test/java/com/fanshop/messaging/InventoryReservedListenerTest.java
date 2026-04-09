package com.fanshop.messaging;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.fanshop.messaging.event.InventoryReservedEvent;
import com.fanshop.messaging.event.PaymentCompletedEvent;
import com.fanshop.messaging.event.PaymentFailedEvent;
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
class InventoryReservedListenerTest {

    @Mock
    private PaymentService paymentService;

    @Mock
    private PaymentEventPublisher paymentEventPublisher;

    @InjectMocks
    private InventoryReservedListener inventoryReservedListener;

    private final InventoryReservedEvent event = new InventoryReservedEvent(1L, 2L, 3L, 1, 50000L);

    @Nested
    @DisplayName("handleInventoryReserved")
    class HandleInventoryReserved {

        @Test
        @DisplayName("결제 승인 시 payment.completed 이벤트를 발행한다")
        void publishCompletedOnApproval() {
            // given
            PaymentCompletedEvent completedEvent = new PaymentCompletedEvent(1L, 2L, 3L, 1);
            given(paymentService.processPayment(event)).willReturn(PaymentResult.approved(completedEvent));

            // when
            inventoryReservedListener.handleInventoryReserved(event);

            // then
            verify(paymentEventPublisher).publishPaymentCompleted(completedEvent);
        }

        @Test
        @DisplayName("결제 실패 시 payment.failed 이벤트를 발행한다")
        void publishFailedOnRejection() {
            // given
            PaymentFailedEvent failedEvent = new PaymentFailedEvent(1L, 2L, 3L, 1, "잔액 부족");
            given(paymentService.processPayment(event)).willReturn(PaymentResult.failed(failedEvent));

            // when
            inventoryReservedListener.handleInventoryReserved(event);

            // then
            verify(paymentEventPublisher).publishPaymentFailed(failedEvent);
        }

    }

}
