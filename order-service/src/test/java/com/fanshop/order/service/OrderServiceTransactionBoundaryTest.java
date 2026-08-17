package com.fanshop.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

import com.fanshop.ContextTest;
import com.fanshop.client.ProductClient;
import com.fanshop.client.ProductResponse;
import com.fanshop.order.api.CreateOrderRequest;
import com.fanshop.support.response.ApiResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fanshop.order.domain.OrderRepository;
import com.fanshop.outbox.OutboxEventRepository;

@TestPropertySource(properties = { "outbox.relay.initial-delay=3600000", "outbox.relay.fixed-delay=3600000",
        "order.expiry.initial-delay=3600000", "order.expiry.fixed-delay=3600000" })
class OrderServiceTransactionBoundaryTest extends ContextTest {

    private final OrderService orderService;

    private final OrderRepository orderRepository;

    private final OutboxEventRepository outboxEventRepository;

    @MockitoBean
    private ProductClient productClient;

    OrderServiceTransactionBoundaryTest(OrderService orderService, OrderRepository orderRepository,
            OutboxEventRepository outboxEventRepository) {
        this.orderService = orderService;
        this.orderRepository = orderRepository;
        this.outboxEventRepository = outboxEventRepository;
    }

    @AfterEach
    void tearDown() {
        orderRepository.deleteAll();
        outboxEventRepository.deleteAll();
    }

    @Test
    @DisplayName("상품 조회는 트랜잭션 밖에서 일어난다 — 네트워크 I/O 동안 DB 커넥션을 붙들지 않는다")
    void fetchesProductOutsideTransaction() {
        boolean[] insideTransaction = { true };
        willAnswer(invocation -> {
            insideTransaction[0] = TransactionSynchronizationManager.isActualTransactionActive();
            return ApiResponse.success(new ProductResponse(3L, "티셔츠", 10000L, 100));
        }).given(productClient).getProduct(3L);

        orderService.createOrder(1L, "key-" + java.util.UUID.randomUUID(), new CreateOrderRequest(3L, 2));

        assertThat(insideTransaction[0]).isFalse();
    }

    @Test
    @DisplayName("주문 저장과 Outbox 기록은 여전히 한 트랜잭션이다")
    void persistsOrderAndOutboxAtomically() {
        given(productClient.getProduct(3L))
            .willReturn(ApiResponse.success(new ProductResponse(3L, "티셔츠", 10000L, 100)));

        orderService.createOrder(1L, "key-" + java.util.UUID.randomUUID(), new CreateOrderRequest(3L, 2));

        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.findAll()).singleElement()
            .satisfies(e -> assertThat(e.getEventType()).isEqualTo("ORDER_CREATED"));
    }

}
