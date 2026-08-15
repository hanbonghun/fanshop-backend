package com.fanshop.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fanshop.common.idempotency.ProcessedEvent;
import com.fanshop.common.idempotency.ProcessedEventRepository;
import com.fanshop.messaging.event.InventoryRejectedEvent;
import com.fanshop.messaging.event.InventoryReservedEvent;
import com.fanshop.messaging.event.OrderCreatedEvent;
import com.fanshop.outbox.OutboxRecorder;
import com.fanshop.product.service.ProductService;
import com.fanshop.product.service.ReservationResult;
import com.fanshop.support.error.ErrorType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderCreatedHandlerTest {

    @Mock
    private ProductService productService;

    @Mock
    private OutboxRecorder outboxRecorder;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @InjectMocks
    private OrderCreatedHandler orderCreatedHandler;

    @Nested
    @DisplayName("handle")
    class Handle {

        @Test
        @DisplayName("재고 선점 성공 시 INVENTORY_RESERVED를 Outbox에 기록한다")
        void success() {
            // given
            OrderCreatedEvent event = new OrderCreatedEvent(1L, 2L, 3L, 4, 50000L);
            given(processedEventRepository.existsByEventIdAndEventType("1", "ORDER_CREATED")).willReturn(false);
            given(productService.softReserveStock(3L, 4)).willReturn(ReservationResult.reserved());

            // when
            orderCreatedHandler.handle(event);

            // then
            verify(outboxRecorder).record("INVENTORY_RESERVED", new InventoryReservedEvent(1L, 2L, 3L, 4, 50000L));
        }

        @Test
        @DisplayName("재고가 부족하면 INVENTORY_REJECTED를 Outbox에 기록한다")
        void rejected() {
            // given
            OrderCreatedEvent event = new OrderCreatedEvent(1L, 2L, 3L, 4, 50000L);
            String reason = ErrorType.INSUFFICIENT_STOCK.getMessage();
            given(processedEventRepository.existsByEventIdAndEventType("1", "ORDER_CREATED")).willReturn(false);
            given(productService.softReserveStock(3L, 4)).willReturn(ReservationResult.rejected(reason));

            // when
            orderCreatedHandler.handle(event);

            // then
            verify(outboxRecorder).record("INVENTORY_REJECTED", new InventoryRejectedEvent(1L, reason));
        }

    }

    @Nested
    @DisplayName("handle — 멱등성")
    class Idempotency {

        @Test
        @DisplayName("이미 처리된 orderId면 재고 선점 없이 무시한다")
        void alreadyProcessed() {
            // given
            OrderCreatedEvent event = new OrderCreatedEvent(1L, 2L, 3L, 4, 50000L);
            given(processedEventRepository.existsByEventIdAndEventType("1", "ORDER_CREATED")).willReturn(true);

            // when
            orderCreatedHandler.handle(event);

            // then
            verify(productService, never()).softReserveStock(anyLong(), anyInt());
            verify(outboxRecorder, never()).record(anyString(), any());
        }

        @Test
        @DisplayName("처음 처리되는 이벤트면 ProcessedEvent를 저장한다")
        void saveProcessedEvent() {
            // given
            OrderCreatedEvent event = new OrderCreatedEvent(1L, 2L, 3L, 4, 50000L);
            given(processedEventRepository.existsByEventIdAndEventType("1", "ORDER_CREATED")).willReturn(false);
            given(productService.softReserveStock(3L, 4)).willReturn(ReservationResult.reserved());

            // when
            orderCreatedHandler.handle(event);

            // then
            verify(processedEventRepository).save(any(ProcessedEvent.class));
            verify(productService).softReserveStock(3L, 4);
        }

    }

}
