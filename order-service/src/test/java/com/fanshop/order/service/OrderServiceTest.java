package com.fanshop.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fanshop.client.ProductClient;
import com.fanshop.client.ProductResponse;
import com.fanshop.kafka.OrderEventPublisher;
import com.fanshop.kafka.event.OrderCreatedEvent;
import com.fanshop.order.api.CreateOrderRequest;
import com.fanshop.order.api.OrderResponse;
import com.fanshop.order.domain.Order;
import com.fanshop.order.domain.OrderRepository;
import com.fanshop.order.domain.OrderStatus;
import com.fanshop.support.error.CoreException;
import com.fanshop.support.error.ErrorType;
import com.fanshop.support.response.ApiResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.HttpClientErrorException;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProductClient productClient;

    @Mock
    private OrderEventPublisher orderEventPublisher;

    @InjectMocks
    private OrderService orderService;

    @Nested
    @DisplayName("createOrder (주문 생성)")
    class CreateOrder {

        @Test
        @DisplayName("상품이 존재하면 PENDING 상태로 주문을 저장하고 order.created 이벤트를 발행한다")
        void success() {
            // given
            Long memberId = 1L;
            CreateOrderRequest request = new CreateOrderRequest(10L, 2);

            ProductResponse product = new ProductResponse(10L, "티셔츠", 29000L, 100);
            given(productClient.getProduct(10L)).willReturn(ApiResponse.success(product));
            given(orderRepository.save(any(Order.class)))
                .willReturn(new Order(memberId, 10L, 2, 58000L, OrderStatus.PENDING));

            // when
            OrderResponse response = orderService.createOrder(memberId, request);

            // then
            assertThat(response.getTotalPrice()).isEqualTo(58000L);
            assertThat(response.getStatus()).isEqualTo(OrderStatus.PENDING);
            verify(orderEventPublisher).publishOrderCreated(any(OrderCreatedEvent.class));
        }

        @Test
        @DisplayName("존재하지 않는 상품이면 PRODUCT_NOT_FOUND 예외를 던진다")
        void productNotFound() {
            // given
            Long memberId = 1L;
            CreateOrderRequest request = new CreateOrderRequest(999L, 2);
            given(productClient.getProduct(999L)).willThrow(HttpClientErrorException.NotFound.class);

            // when & then
            assertThatThrownBy(() -> orderService.createOrder(memberId, request)).isInstanceOf(CoreException.class)
                .satisfies(e -> assertThat(((CoreException) e).getErrorType()).isEqualTo(ErrorType.PRODUCT_NOT_FOUND));

            verify(orderRepository, never()).save(any());
            verify(orderEventPublisher, never()).publishOrderCreated(any());
        }

    }

    @Nested
    @DisplayName("confirmOrder (주문 확정)")
    class ConfirmOrder {

        @Test
        @DisplayName("PENDING 주문을 CONFIRMED로 변경한다")
        void success() {
            // given
            Long orderId = 1L;
            Order order = new Order(1L, 10L, 2, 58000L, OrderStatus.PENDING);
            given(orderRepository.findById(orderId)).willReturn(java.util.Optional.of(order));

            // when
            orderService.confirmOrder(orderId);

            // then
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        }

        @Test
        @DisplayName("존재하지 않는 주문이면 ORDER_NOT_FOUND 예외를 던진다")
        void orderNotFound() {
            // given
            given(orderRepository.findById(999L)).willReturn(java.util.Optional.empty());

            // when & then
            assertThatThrownBy(() -> orderService.confirmOrder(999L)).isInstanceOf(CoreException.class)
                .satisfies(e -> assertThat(((CoreException) e).getErrorType()).isEqualTo(ErrorType.ORDER_NOT_FOUND));
        }

    }

    @Nested
    @DisplayName("cancelOrder (주문 취소)")
    class CancelOrder {

        @Test
        @DisplayName("PENDING 주문을 CANCELLED로 변경한다")
        void success() {
            // given
            Long orderId = 1L;
            Order order = new Order(1L, 10L, 2, 58000L, OrderStatus.PENDING);
            given(orderRepository.findById(orderId)).willReturn(java.util.Optional.of(order));

            // when
            orderService.cancelOrder(orderId, "재고 부족");

            // then
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }

    }

}
