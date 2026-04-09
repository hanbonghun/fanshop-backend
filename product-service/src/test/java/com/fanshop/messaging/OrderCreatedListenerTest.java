package com.fanshop.messaging;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.fanshop.messaging.event.OrderCreatedEvent;
import com.fanshop.messaging.event.StockResultEvent;
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
        @DisplayName("재고 감소에 성공하면 성공 이벤트를 발행한다")
        void success() {
            // given
            OrderCreatedEvent event = new OrderCreatedEvent(1L, 2L, 3L, 4);

            // when
            orderCreatedListener.handleOrderCreated(event);

            // then
            verify(productService).decreaseStock(3L, 4);
            verify(stockEventPublisher).publishStockResult(new StockResultEvent(1L, true, null));
        }

        @Test
        @DisplayName("재고 감소에 실패하면 실패 이벤트를 발행한다")
        void fail() {
            // given
            OrderCreatedEvent event = new OrderCreatedEvent(1L, 2L, 3L, 4);
            CoreException exception = new CoreException(ErrorType.INSUFFICIENT_STOCK, 3L);
            doThrow(exception).when(productService).decreaseStock(3L, 4);

            // when
            orderCreatedListener.handleOrderCreated(event);

            // then
            verify(stockEventPublisher).publishStockResult(new StockResultEvent(1L, false, exception.getMessage()));
        }

    }

}
