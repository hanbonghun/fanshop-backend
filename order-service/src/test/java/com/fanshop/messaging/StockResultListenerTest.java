package com.fanshop.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fanshop.common.idempotency.ProcessedEvent;
import com.fanshop.common.idempotency.ProcessedEventRepository;
import com.fanshop.messaging.event.InventoryRejectedEvent;
import com.fanshop.messaging.event.InventoryReservedEvent;
import com.fanshop.messaging.event.PaymentCompletedEvent;
import com.fanshop.messaging.event.PaymentFailedEvent;
import com.fanshop.order.service.OrderService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StockResultListenerTest {

    @Mock
    private OrderService orderService;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @InjectMocks
    private StockResultListener stockResultListener;

    @Nested
    @DisplayName("handleInventoryReserved — 멱등성")
    class InventoryReserved {

        private final InventoryReservedEvent event = new InventoryReservedEvent(1L, 2L, 3L, 1, 50000L);

        @Test
        @DisplayName("처음 수신 시 waitForPayment를 호출한다")
        void firstTime() {
            // given
            given(processedEventRepository.existsByEventIdAndEventType("1", "INVENTORY_RESERVED")).willReturn(false);

            // when
            stockResultListener.handleInventoryReserved(event);

            // then
            verify(processedEventRepository).save(any(ProcessedEvent.class));
            verify(orderService).waitForPayment(1L);
        }

        @Test
        @DisplayName("중복 수신 시 waitForPayment를 호출하지 않는다")
        void duplicate() {
            // given
            given(processedEventRepository.existsByEventIdAndEventType("1", "INVENTORY_RESERVED")).willReturn(true);

            // when
            stockResultListener.handleInventoryReserved(event);

            // then
            verify(orderService, never()).waitForPayment(anyLong());
        }

    }

    @Nested
    @DisplayName("handleInventoryRejected — 멱등성")
    class InventoryRejected {

        private final InventoryRejectedEvent event = new InventoryRejectedEvent(1L, "재고 부족");

        @Test
        @DisplayName("처음 수신 시 cancelOrder를 호출한다")
        void firstTime() {
            // given
            given(processedEventRepository.existsByEventIdAndEventType("1", "INVENTORY_REJECTED")).willReturn(false);

            // when
            stockResultListener.handleInventoryRejected(event);

            // then
            verify(processedEventRepository).save(any(ProcessedEvent.class));
            verify(orderService).cancelOrder(1L, "재고 부족");
        }

        @Test
        @DisplayName("중복 수신 시 cancelOrder를 호출하지 않는다")
        void duplicate() {
            // given
            given(processedEventRepository.existsByEventIdAndEventType("1", "INVENTORY_REJECTED")).willReturn(true);

            // when
            stockResultListener.handleInventoryRejected(event);

            // then
            verify(orderService, never()).cancelOrder(anyLong(), anyString());
        }

    }

    @Nested
    @DisplayName("handlePaymentCompleted — 멱등성")
    class PaymentCompleted {

        private final PaymentCompletedEvent event = new PaymentCompletedEvent(1L, 2L, 3L, 1);

        @Test
        @DisplayName("처음 수신 시 confirmOrder를 호출한다")
        void firstTime() {
            // given
            given(processedEventRepository.existsByEventIdAndEventType("1", "PAYMENT_COMPLETED")).willReturn(false);

            // when
            stockResultListener.handlePaymentCompleted(event);

            // then
            verify(processedEventRepository).save(any(ProcessedEvent.class));
            verify(orderService).confirmOrder(1L);
        }

        @Test
        @DisplayName("중복 수신 시 confirmOrder를 호출하지 않는다")
        void duplicate() {
            // given
            given(processedEventRepository.existsByEventIdAndEventType("1", "PAYMENT_COMPLETED")).willReturn(true);

            // when
            stockResultListener.handlePaymentCompleted(event);

            // then
            verify(orderService, never()).confirmOrder(anyLong());
        }

    }

    @Nested
    @DisplayName("handlePaymentFailed — 멱등성")
    class PaymentFailed {

        private final PaymentFailedEvent event = new PaymentFailedEvent(1L, 2L, 3L, 1, "잔액 부족");

        @Test
        @DisplayName("처음 수신 시 cancelOrder를 호출한다")
        void firstTime() {
            // given
            given(processedEventRepository.existsByEventIdAndEventType("1", "PAYMENT_FAILED")).willReturn(false);

            // when
            stockResultListener.handlePaymentFailed(event);

            // then
            verify(processedEventRepository).save(any(ProcessedEvent.class));
            verify(orderService).cancelOrder(1L, "잔액 부족");
        }

        @Test
        @DisplayName("중복 수신 시 cancelOrder를 호출하지 않는다")
        void duplicate() {
            // given
            given(processedEventRepository.existsByEventIdAndEventType("1", "PAYMENT_FAILED")).willReturn(true);

            // when
            stockResultListener.handlePaymentFailed(event);

            // then
            verify(orderService, never()).cancelOrder(anyLong(), anyString());
        }

    }

}
