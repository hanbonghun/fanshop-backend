package com.fanshop.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fanshop.common.idempotency.ProcessedEvent;
import com.fanshop.common.idempotency.ProcessedEventRepository;
import com.fanshop.messaging.event.InventoryRejectedEvent;
import com.fanshop.messaging.event.InventoryReservedEvent;
import com.fanshop.messaging.event.OrderCreatedEvent;
import com.fanshop.product.service.ProductService;
import com.fanshop.support.error.CoreException;
import com.fanshop.support.error.ErrorType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderCreatedListenerTest {

    @Mock
    private ProductService productService;

    @Mock
    private StockEventPublisher stockEventPublisher;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @InjectMocks
    private OrderCreatedListener orderCreatedListener;

    @Nested
    @DisplayName("handleOrderCreated")
    class HandleOrderCreated {

        @Test
        @DisplayName("재고 선점 성공 시 inventory.reserved 이벤트를 발행한다")
        void success() {
            // given
            OrderCreatedEvent event = new OrderCreatedEvent(1L, 2L, 3L, 4, 50000L);
            given(processedEventRepository.existsByEventIdAndEventType("1", "ORDER_CREATED")).willReturn(false);

            // when
            orderCreatedListener.handleOrderCreated(event);

            // then
            verify(productService).softReserveStock(3L, 4);
            verify(stockEventPublisher).publishInventoryReserved(new InventoryReservedEvent(1L, 2L, 3L, 4, 50000L));
        }

        @Test
        @DisplayName("재고 부족 시 inventory.rejected 이벤트를 발행한다")
        void fail() {
            // given
            OrderCreatedEvent event = new OrderCreatedEvent(1L, 2L, 3L, 4, 50000L);
            given(processedEventRepository.existsByEventIdAndEventType("1", "ORDER_CREATED")).willReturn(false);
            CoreException exception = new CoreException(ErrorType.INSUFFICIENT_STOCK, 3L);
            doThrow(exception).when(productService).softReserveStock(3L, 4);

            // when
            orderCreatedListener.handleOrderCreated(event);

            // then
            verify(stockEventPublisher)
                .publishInventoryRejected(new InventoryRejectedEvent(1L, exception.getMessage()));
        }

    }

    @Nested
    @DisplayName("handleOrderCreated — 멱등성")
    class Idempotency {

        @Test
        @DisplayName("이미 처리된 orderId면 재고 선점 없이 무시한다")
        void alreadyProcessed() {
            // given
            OrderCreatedEvent event = new OrderCreatedEvent(1L, 2L, 3L, 4, 50000L);
            given(processedEventRepository.existsByEventIdAndEventType("1", "ORDER_CREATED")).willReturn(true);

            // when
            orderCreatedListener.handleOrderCreated(event);

            // then
            verify(productService, never()).softReserveStock(anyLong(), anyInt());
            verify(stockEventPublisher, never()).publishInventoryReserved(any());
            verify(stockEventPublisher, never()).publishInventoryRejected(any());
        }

        @Test
        @DisplayName("처음 처리되는 이벤트면 ProcessedEvent를 저장한다")
        void saveProcessedEvent() {
            // given
            OrderCreatedEvent event = new OrderCreatedEvent(1L, 2L, 3L, 4, 50000L);
            given(processedEventRepository.existsByEventIdAndEventType("1", "ORDER_CREATED")).willReturn(false);

            // when
            orderCreatedListener.handleOrderCreated(event);

            // then
            verify(processedEventRepository).save(any(ProcessedEvent.class));
            verify(productService).softReserveStock(3L, 4);
        }

    }

}
