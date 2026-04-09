package com.fanshop.messaging;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

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
            CoreException exception = new CoreException(ErrorType.INSUFFICIENT_STOCK, 3L);
            doThrow(exception).when(productService).softReserveStock(3L, 4);

            // when
            orderCreatedListener.handleOrderCreated(event);

            // then
            verify(stockEventPublisher)
                .publishInventoryRejected(new InventoryRejectedEvent(1L, exception.getMessage()));
        }

    }

}
