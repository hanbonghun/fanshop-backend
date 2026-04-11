package com.fanshop.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fanshop.common.idempotency.ProcessedEvent;
import com.fanshop.common.idempotency.ProcessedEventRepository;
import com.fanshop.messaging.event.PaymentCompletedEvent;
import com.fanshop.messaging.event.PaymentFailedEvent;
import com.fanshop.product.service.ProductService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentResultListenerTest {

    @Mock
    private ProductService productService;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @InjectMocks
    private PaymentResultListener paymentResultListener;

    @Nested
    @DisplayName("handlePaymentCompleted")
    class HandlePaymentCompleted {

        private final PaymentCompletedEvent event = new PaymentCompletedEvent(1L, 2L, 3L, 1);

        @Test
        @DisplayName("처음 수신 시 재고를 확정한다")
        void firstTime() {
            // given
            given(processedEventRepository.existsByEventIdAndEventType("1", "PAYMENT_COMPLETED")).willReturn(false);

            // when
            paymentResultListener.handlePaymentCompleted(event);

            // then
            verify(processedEventRepository).save(any(ProcessedEvent.class));
            verify(productService).confirmReservation(3L, 1);
        }

        @Test
        @DisplayName("중복 수신 시 재고 확정 없이 무시한다")
        void duplicate() {
            // given
            given(processedEventRepository.existsByEventIdAndEventType("1", "PAYMENT_COMPLETED")).willReturn(true);

            // when
            paymentResultListener.handlePaymentCompleted(event);

            // then
            verify(productService, never()).confirmReservation(anyLong(), anyInt());
        }

    }

    @Nested
    @DisplayName("handlePaymentFailed")
    class HandlePaymentFailed {

        private final PaymentFailedEvent event = new PaymentFailedEvent(1L, 2L, 3L, 1, "잔액 부족");

        @Test
        @DisplayName("처음 수신 시 재고를 복원한다")
        void firstTime() {
            // given
            given(processedEventRepository.existsByEventIdAndEventType("1", "PAYMENT_FAILED")).willReturn(false);

            // when
            paymentResultListener.handlePaymentFailed(event);

            // then
            verify(processedEventRepository).save(any(ProcessedEvent.class));
            verify(productService).releaseReservation(3L, 1);
        }

        @Test
        @DisplayName("중복 수신 시 재고 복원 없이 무시한다")
        void duplicate() {
            // given
            given(processedEventRepository.existsByEventIdAndEventType("1", "PAYMENT_FAILED")).willReturn(true);

            // when
            paymentResultListener.handlePaymentFailed(event);

            // then
            verify(productService, never()).releaseReservation(anyLong(), anyInt());
        }

    }

}
