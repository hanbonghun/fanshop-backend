package com.fanshop.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OrderTest {

    private Order orderWith(OrderStatus status) {
        return new Order(1L, 2L, 3, 30000L, status);
    }

    @Nested
    @DisplayName("expire")
    class Expire {

        @Test
        @DisplayName("결제 대기 중인 주문은 만료시킬 수 있다")
        void fromWaitingPayment() {
            Order order = orderWith(OrderStatus.WAITING_PAYMENT);

            order.expire();

            assertThat(order.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        }

        @Test
        @DisplayName("이미 확정된 주문은 만료시킬 수 없다")
        void fromConfirmed() {
            Order order = orderWith(OrderStatus.CONFIRMED);

            assertThatThrownBy(order::expire).isInstanceOf(IllegalStateException.class);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        }

    }

    @Nested
    @DisplayName("onPaymentCompleted")
    class OnPaymentCompleted {

        @Test
        @DisplayName("결제 대기 중이면 확정된다")
        void fromWaitingPayment() {
            Order order = orderWith(OrderStatus.WAITING_PAYMENT);

            order.onPaymentCompleted();

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        }

        @Test
        @DisplayName("만료된 뒤 결제가 도착하면 환불 대상이 된다 — 재고는 이미 해제되어 되돌리지 않는다")
        void fromExpired() {
            Order order = orderWith(OrderStatus.EXPIRED);

            order.onPaymentCompleted();

            assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUND_REQUIRED);
        }

        @Test
        @DisplayName("취소된 주문은 확정으로 되돌릴 수 없다")
        void fromCancelled() {
            Order order = orderWith(OrderStatus.CANCELLED);

            assertThatThrownBy(order::onPaymentCompleted).isInstanceOf(IllegalStateException.class);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }

    }

    @Nested
    @DisplayName("cancel")
    class Cancel {

        @Test
        @DisplayName("결제 대기 중인 주문은 취소할 수 있다")
        void fromWaitingPayment() {
            Order order = orderWith(OrderStatus.WAITING_PAYMENT);

            order.cancel();

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("만료된 주문은 취소할 수 없다 — 이미 종결 상태다")
        void fromExpired() {
            Order order = orderWith(OrderStatus.EXPIRED);

            assertThatThrownBy(order::cancel).isInstanceOf(IllegalStateException.class);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        }

    }

}
