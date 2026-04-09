package com.fanshop.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fanshop.client.ProductClient;
import com.fanshop.client.ProductResponse;
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

	@InjectMocks
	private OrderService orderService;

	@Nested
	@DisplayName("createOrder (주문 생성)")
	class CreateOrder {

		@Test
		@DisplayName("재고가 충분하면 주문을 저장하고 재고를 감소시킨다")
		void success() {
			// given
			Long memberId = 1L;
			CreateOrderRequest request = new CreateOrderRequest(10L, 2);

			ProductResponse product = new ProductResponse(10L, "티셔츠", 29000L, 100);
			given(productClient.getProduct(10L)).willReturn(ApiResponse.success(product));
			given(orderRepository.save(any(Order.class)))
				.willReturn(new Order(memberId, 10L, 2, 58000L, OrderStatus.CONFIRMED));

			// when
			OrderResponse response = orderService.createOrder(memberId, request);

			// then
			assertThat(response.getTotalPrice()).isEqualTo(58000L);
			assertThat(response.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
			verify(productClient).decreaseStock(10L, 2);
		}

		@Test
		@DisplayName("존재하지 않는 상품이면 PRODUCT_NOT_FOUND 예외를 던진다")
		void productNotFound() {
			// given
			Long memberId = 1L;
			CreateOrderRequest request = new CreateOrderRequest(999L, 2);
			given(productClient.getProduct(999L)).willThrow(HttpClientErrorException.NotFound.class);

			// when & then
			assertThatThrownBy(() -> orderService.createOrder(memberId, request))
				.isInstanceOf(CoreException.class)
				.satisfies(e -> assertThat(((CoreException) e).getErrorType())
					.isEqualTo(ErrorType.PRODUCT_NOT_FOUND));

			verify(orderRepository, never()).save(any());
		}

		@Test
		@DisplayName("재고가 부족하면 INSUFFICIENT_STOCK 예외를 던진다")
		void insufficientStock() {
			// given
			Long memberId = 1L;
			CreateOrderRequest request = new CreateOrderRequest(10L, 50);

			ProductResponse product = new ProductResponse(10L, "티셔츠", 29000L, 5);
			given(productClient.getProduct(10L)).willReturn(ApiResponse.success(product));

			// when & then
			assertThatThrownBy(() -> orderService.createOrder(memberId, request))
				.isInstanceOf(CoreException.class)
				.satisfies(e -> assertThat(((CoreException) e).getErrorType())
					.isEqualTo(ErrorType.INSUFFICIENT_STOCK));

			verify(orderRepository, never()).save(any());
		}

	}

}
