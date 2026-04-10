package com.fanshop.messaging;

import static org.mockito.Mockito.verify;

import com.fanshop.messaging.event.PaymentCompletedEvent;
import com.fanshop.messaging.event.PaymentFailedEvent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.stream.function.StreamBridge;

@ExtendWith(MockitoExtension.class)
class PaymentEventPublisherTest {

    @Mock
    private StreamBridge streamBridge;

    @InjectMocks
    private PaymentEventPublisher paymentEventPublisher;

    @Test
    @DisplayName("payment.completed 이벤트를 output binding으로 발행한다")
    void publishPaymentCompleted() {
        PaymentCompletedEvent event = new PaymentCompletedEvent(1L, 2L, 3L, 1);

        paymentEventPublisher.publishPaymentCompleted(event);

        verify(streamBridge).send("paymentCompleted-out-0", event);
    }

    @Test
    @DisplayName("payment.failed 이벤트를 output binding으로 발행한다")
    void publishPaymentFailed() {
        PaymentFailedEvent event = new PaymentFailedEvent(1L, 2L, 3L, 1, "잔액 부족");

        paymentEventPublisher.publishPaymentFailed(event);

        verify(streamBridge).send("paymentFailed-out-0", event);
    }

}
