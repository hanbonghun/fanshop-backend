package com.fanshop.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willAnswer;

import java.time.LocalDateTime;

import com.fanshop.ContextTest;
import com.fanshop.messaging.event.OrderExpiredEvent;
import com.fanshop.order.domain.Order;
import com.fanshop.order.domain.OrderRepository;
import com.fanshop.order.domain.OrderStatus;
import com.fanshop.outbox.OutboxEventRepository;
import com.fanshop.outbox.OutboxRecorder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@TestPropertySource(properties = { "outbox.relay.initial-delay=3600000", "outbox.relay.fixed-delay=3600000",
        "order.expiry.initial-delay=3600000", "order.expiry.fixed-delay=3600000" })
class OrderExpirySweeperTest extends ContextTest {

    private final OrderExpirySweeper sweeper;

    private final OrderRepository orderRepository;

    private final OutboxEventRepository outboxEventRepository;

    @MockitoSpyBean
    private OutboxRecorder outboxRecorder;

    OrderExpirySweeperTest(OrderExpirySweeper sweeper, OrderRepository orderRepository,
            OutboxEventRepository outboxEventRepository) {
        this.sweeper = sweeper;
        this.orderRepository = orderRepository;
        this.outboxEventRepository = outboxEventRepository;
    }

    @AfterEach
    void tearDown() {
        orderRepository.deleteAll();
        outboxEventRepository.deleteAll();
    }

    @Test
    @DisplayName("임계 시각보다 오래 대기한 주문을 EXPIRED로 바꾸고 해제 이벤트를 Outbox에 남긴다")
    void expiresStaleOrder() {
        Order stale = orderRepository.save(new Order(1L, 2L, 3, 30000L, OrderStatus.WAITING_PAYMENT));

        int expired = sweeper.expireBefore(LocalDateTime.now().plusSeconds(1));

        assertThat(expired).isEqualTo(1);
        assertThat(orderRepository.findById(stale.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(outboxEventRepository.findAll()).singleElement()
            .satisfies(e -> assertThat(e.getEventType()).isEqualTo("ORDER_EXPIRED"));
    }

    @Test
    @DisplayName("임계 시각을 지나지 않은 주문은 건드리지 않는다")
    void keepsFreshOrder() {
        Order fresh = orderRepository.save(new Order(1L, 2L, 3, 30000L, OrderStatus.WAITING_PAYMENT));

        int expired = sweeper.expireBefore(LocalDateTime.now().minusDays(1));

        assertThat(expired).isZero();
        assertThat(orderRepository.findById(fresh.getId()).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.WAITING_PAYMENT);
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    @DisplayName("해제 이벤트 기록이 실패하면 만료도 함께 롤백된다 — 만료만 되고 재고가 안 풀리는 것을 막는다")
    void rollsBackExpiryWhenOutboxRecordFails() {
        Order stale = orderRepository.save(new Order(1L, 2L, 3, 30000L, OrderStatus.WAITING_PAYMENT));
        // record()가 성공한 **뒤에** 실패시킨다. 앞에서 실패시키면 바깥 트랜잭션이 없을 때도 Outbox 행이
        // 안 생겨 테스트가 잘못된 이유로 통과한다 — 경계를 검증하려면 커밋 가능한 지점을 지나야 한다.
        willAnswer(invocation -> {
            invocation.callRealMethod();
            throw new RuntimeException("기록 직후 실패");
        }).given(outboxRecorder).record(anyString(), any(OrderExpiredEvent.class));

        assertThatThrownBy(() -> sweeper.expireBefore(LocalDateTime.now().plusSeconds(1)))
            .isInstanceOf(RuntimeException.class);

        assertThat(orderRepository.findById(stale.getId()).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.WAITING_PAYMENT);
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    @DisplayName("결제 대기가 아닌 주문은 오래됐어도 만료 대상이 아니다")
    void ignoresOtherStatuses() {
        orderRepository.save(new Order(1L, 2L, 3, 30000L, OrderStatus.CONFIRMED));
        orderRepository.save(new Order(1L, 2L, 3, 30000L, OrderStatus.PENDING));

        int expired = sweeper.expireBefore(LocalDateTime.now().plusSeconds(1));

        assertThat(expired).isZero();
        assertThat(outboxEventRepository.count()).isZero();
    }

}
