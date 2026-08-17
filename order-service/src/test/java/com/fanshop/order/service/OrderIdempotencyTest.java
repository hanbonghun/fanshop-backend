package com.fanshop.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fanshop.ContextTest;
import com.fanshop.client.ProductClient;
import com.fanshop.client.ProductResponse;
import com.fanshop.support.response.ApiResponse;
import com.fanshop.messaging.OrderEventPublisher;
import com.fanshop.order.api.CreateOrderRequest;
import com.fanshop.order.api.OrderResponse;
import com.fanshop.order.domain.OrderRepository;
import com.fanshop.outbox.OutboxEventRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 주문 생성은 본질적으로 멱등하지 않다. 같은 요청이 두 번 도달하면 주문도 두 건이 되고, 이후 재고 예약도 두 번 일어난다.
 * <p>
 * 이벤트 소비 쪽에는 {@code processed_events}로 멱등성이 있었지만 API 진입점에는 없었다. 더블클릭, 타임아웃 뒤 클라이언트 재시도,
 * 뒤로가기 후 재제출은 트래픽 규모와 무관하게 발생한다.
 */
@TestPropertySource(properties = { "outbox.relay.initial-delay=3600000", "outbox.relay.fixed-delay=3600000",
        "order.expiry.threshold-minutes=999999" })
class OrderIdempotencyTest extends ContextTest {

    private static final String KEY = "idem-key-0001";

    private final OrderService orderService;

    private final OrderRepository orderRepository;

    private final OutboxEventRepository outboxEventRepository;

    @MockitoBean
    private ProductClient productClient;

    @MockitoBean
    private OrderEventPublisher orderEventPublisher;

    OrderIdempotencyTest(OrderService orderService, OrderRepository orderRepository,
            OutboxEventRepository outboxEventRepository) {
        this.orderService = orderService;
        this.orderRepository = orderRepository;
        this.outboxEventRepository = outboxEventRepository;
    }

    @BeforeEach
    void setUp() {
        given(productClient.getProduct(anyLong()))
            .willReturn(ApiResponse.success(new ProductResponse(1L, "상품", 50000L, 100)));
    }

    @AfterEach
    void tearDown() {
        orderRepository.deleteAll();
        outboxEventRepository.deleteAll();
    }

    private CreateOrderRequest request() {
        return new CreateOrderRequest(1L, 2);
    }

    @Test
    @DisplayName("같은 멱등키로 두 번 요청하면 주문은 한 건만 생성된다")
    void createsOnlyOneOrderForSameKey() {
        orderService.createOrder(1L, KEY, request());
        orderService.createOrder(1L, KEY, request());

        assertThat(orderRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 멱등키 재요청은 기존 주문을 그대로 돌려준다")
    void returnsExistingOrderForSameKey() {
        OrderResponse first = orderService.createOrder(1L, KEY, request());

        OrderResponse second = orderService.createOrder(1L, KEY, request());

        assertThat(second.getId()).isEqualTo(first.getId());
    }

    @Test
    @DisplayName("재요청은 발행 예약도 다시 만들지 않는다 — 같은 주문으로 SAGA가 두 번 시작되지 않는다")
    void doesNotRecordOutboxTwice() {
        orderService.createOrder(1L, KEY, request());

        orderService.createOrder(1L, KEY, request());

        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 키로 8개 스레드가 동시에 요청해도 주문은 한 건만 생성된다")
    void createsOnlyOneOrderUnderConcurrency() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Void>> tasks = java.util.Collections.nCopies(threads, () -> {
            try {
                orderService.createOrder(1L, KEY, request());
            }
            catch (Exception ignored) {
                // 경쟁에서 진 요청이 예외로 끝나더라도 주문 수가 늘지 않는 것이 이 테스트의 관심사다.
            }
            return null;
        });

        List<Future<Void>> futures = pool.invokeAll(tasks);
        for (Future<Void> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(orderRepository.count()).isEqualTo(1);
    }

}
