package com.fanshop.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.willThrow;

import com.fanshop.ContextTest;
import com.fanshop.common.idempotency.ProcessedEventRepository;
import com.fanshop.messaging.event.PaymentFailedEvent;
import com.fanshop.product.service.ProductService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@TestPropertySource(properties = { "outbox.relay.initial-delay=3600000", "outbox.relay.fixed-delay=3600000" })
class PaymentResultHandlerAtomicityTest extends ContextTest {

    private final PaymentResultHandler handler;

    private final ProcessedEventRepository processedEventRepository;

    @MockitoBean
    private ProductService productService;

    PaymentResultHandlerAtomicityTest(PaymentResultHandler handler, ProcessedEventRepository processedEventRepository) {
        this.handler = handler;
        this.processedEventRepository = processedEventRepository;
    }

    @AfterEach
    void tearDown() {
        processedEventRepository.deleteAll();
    }

    @Test
    @DisplayName("재고 해제가 일시 실패하면 멱등성 기록도 롤백된다 — 예약이 영구히 잠기는 것을 막는다")
    void rollsBackProcessedEventOnTransientFailure() {
        willThrow(new RuntimeException("DB 일시 오류")).given(productService).releaseReservation(anyLong(), anyInt());

        assertThatThrownBy(() -> handler.handlePaymentFailed(new PaymentFailedEvent(1L, 2L, 3L, 4, "잔액 부족")))
            .isInstanceOf(RuntimeException.class);

        assertThat(processedEventRepository.existsByEventIdAndEventType("1", "PAYMENT_FAILED")).isFalse();
    }

    @Test
    @DisplayName("정상 처리되면 멱등성 기록이 커밋된다")
    void commitsProcessedEventOnSuccess() {
        handler.handlePaymentFailed(new PaymentFailedEvent(1L, 2L, 3L, 4, "잔액 부족"));

        assertThat(processedEventRepository.existsByEventIdAndEventType("1", "PAYMENT_FAILED")).isTrue();
    }

}
