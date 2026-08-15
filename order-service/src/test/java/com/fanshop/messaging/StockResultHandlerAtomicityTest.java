package com.fanshop.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.willThrow;

import com.fanshop.ContextTest;
import com.fanshop.common.idempotency.ProcessedEventRepository;
import com.fanshop.messaging.event.PaymentCompletedEvent;
import com.fanshop.order.service.OrderService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@TestPropertySource(properties = { "outbox.relay.initial-delay=3600000", "outbox.relay.fixed-delay=3600000" })
class StockResultHandlerAtomicityTest extends ContextTest {

    private final StockResultHandler handler;

    private final ProcessedEventRepository processedEventRepository;

    @MockitoBean
    private OrderService orderService;

    StockResultHandlerAtomicityTest(StockResultHandler handler, ProcessedEventRepository processedEventRepository) {
        this.handler = handler;
        this.processedEventRepository = processedEventRepository;
    }

    @AfterEach
    void tearDown() {
        processedEventRepository.deleteAll();
    }

    @Test
    @DisplayName("주문 확정이 일시 실패하면 멱등성 기록도 롤백되어 재시도가 정상 동작한다")
    void rollsBackProcessedEventOnTransientFailure() {
        willThrow(new RuntimeException("DB 일시 오류")).given(orderService).confirmOrder(anyLong());

        assertThatThrownBy(() -> handler.handlePaymentCompleted(new PaymentCompletedEvent(1L, 2L, 3L, 4)))
            .isInstanceOf(RuntimeException.class);

        assertThat(processedEventRepository.existsByEventIdAndEventType("1", "PAYMENT_COMPLETED")).isFalse();
    }

    @Test
    @DisplayName("정상 처리되면 멱등성 기록이 커밋된다")
    void commitsProcessedEventOnSuccess() {
        handler.handlePaymentCompleted(new PaymentCompletedEvent(1L, 2L, 3L, 4));

        assertThat(processedEventRepository.existsByEventIdAndEventType("1", "PAYMENT_COMPLETED")).isTrue();
    }

}
