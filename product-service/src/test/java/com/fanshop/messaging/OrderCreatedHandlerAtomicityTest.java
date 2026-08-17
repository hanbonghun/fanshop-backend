package com.fanshop.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import com.fanshop.ContextTest;
import com.fanshop.common.idempotency.ProcessedEventRepository;
import com.fanshop.messaging.event.OrderCreatedEvent;
import com.fanshop.outbox.OutboxEventRepository;
import com.fanshop.product.service.ProductService;
import com.fanshop.product.service.ReservationResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@TestPropertySource(properties = { "outbox.relay.initial-delay=3600000", "outbox.relay.fixed-delay=3600000" })
class OrderCreatedHandlerAtomicityTest extends ContextTest {

    private final OrderCreatedHandler handler;

    private final ProcessedEventRepository processedEventRepository;

    private final OutboxEventRepository outboxEventRepository;

    @MockitoBean
    private ProductService productService;

    OrderCreatedHandlerAtomicityTest(OrderCreatedHandler handler, ProcessedEventRepository processedEventRepository,
            OutboxEventRepository outboxEventRepository) {
        this.handler = handler;
        this.processedEventRepository = processedEventRepository;
        this.outboxEventRepository = outboxEventRepository;
    }

    @AfterEach
    void tearDown() {
        processedEventRepository.deleteAll();
        outboxEventRepository.deleteAll();
    }

    @Test
    @DisplayName("비즈니스 처리가 일시 실패하면 멱등성 기록도 롤백되어 재시도가 정상 동작한다")
    void rollsBackProcessedEventOnTransientFailure() {
        OrderCreatedEvent event = new OrderCreatedEvent(1L, 2L, 3L, 4, 50000L);
        given(productService.softReserveStock(anyLong(), anyLong(), anyInt()))
            .willThrow(new RuntimeException("DB 일시 오류"));

        assertThatThrownBy(() -> handler.handle(event)).isInstanceOf(RuntimeException.class);

        assertThat(processedEventRepository.existsByEventIdAndEventType("1", "ORDER_CREATED")).isFalse();
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    @DisplayName("재고 부족은 롤백 없이 INVENTORY_REJECTED가 Outbox에 저장된다")
    void recordsRejectedWithoutRollback() {
        OrderCreatedEvent event = new OrderCreatedEvent(1L, 2L, 3L, 4, 50000L);
        given(productService.softReserveStock(1L, 3L, 4)).willReturn(ReservationResult.rejected("Insufficient stock."));

        handler.handle(event);

        assertThat(processedEventRepository.existsByEventIdAndEventType("1", "ORDER_CREATED")).isTrue();
        assertThat(outboxEventRepository.findAll()).singleElement()
            .satisfies(e -> assertThat(e.getEventType()).isEqualTo("INVENTORY_REJECTED"));
    }

    @Test
    @DisplayName("예약 성공 시 INVENTORY_RESERVED가 Outbox에 저장된다")
    void recordsReserved() {
        OrderCreatedEvent event = new OrderCreatedEvent(1L, 2L, 3L, 4, 50000L);
        given(productService.softReserveStock(1L, 3L, 4)).willReturn(ReservationResult.reserved());

        handler.handle(event);

        assertThat(outboxEventRepository.findAll()).singleElement()
            .satisfies(e -> assertThat(e.getEventType()).isEqualTo("INVENTORY_RESERVED"));
    }

}
