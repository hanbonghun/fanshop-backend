package com.fanshop.order.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fanshop.ContextTest;
import com.fanshop.auth.JwtTokenProvider;
import com.fanshop.client.ProductClient;
import com.fanshop.client.ProductResponse;
import com.fanshop.order.domain.OrderStatus;
import com.fanshop.support.response.ApiResponse;

import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;

@AutoConfigureMockMvc
class OrderControllerTest extends ContextTest {

	private final MockMvc mockMvc;

	private final ObjectMapper objectMapper;

	private final JwtTokenProvider jwtTokenProvider;

	@MockitoBean
	private ProductClient productClient;

	OrderControllerTest(MockMvc mockMvc, ObjectMapper objectMapper, JwtTokenProvider jwtTokenProvider) {
		this.mockMvc = mockMvc;
		this.objectMapper = objectMapper;
		this.jwtTokenProvider = jwtTokenProvider;
	}

	private String bearerToken(Long memberId) {
		return "Bearer " + jwtTokenProvider.generate(memberId, "test@email.com");
	}

	@Nested
	@DisplayName("POST /api/v1/orders")
	class CreateOrder {

		@Test
		@DisplayName("유효한 JWT와 충분한 재고면 200 OK와 주문 정보를 반환한다")
		void success() throws Exception {
			// given
			ProductResponse product = new ProductResponse(1L, "티셔츠", 29000L, 100);
			given(productClient.getProduct(1L)).willReturn(ApiResponse.success(product));
			given(productClient.decreaseStock(eq(1L), eq(2))).willReturn(ApiResponse.success(null));

			CreateOrderRequest request = new CreateOrderRequest(1L, 2);

			// when & then
			mockMvc
				.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
					.header(HttpHeaders.AUTHORIZATION, bearerToken(1L))
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalPrice").value(58000))
				.andExpect(jsonPath("$.data.status").value(OrderStatus.CONFIRMED.name()));
		}

		@Test
		@DisplayName("JWT 없이 요청하면 401 Unauthorized를 반환한다")
		void unauthorized() throws Exception {
			// given
			CreateOrderRequest request = new CreateOrderRequest(1L, 2);

			// when & then
			mockMvc
				.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isUnauthorized());
		}

		@Test
		@DisplayName("존재하지 않는 상품이면 404 Not Found를 반환한다")
		void productNotFound() throws Exception {
			// given
			given(productClient.getProduct(999L)).willThrow(HttpClientErrorException.NotFound.class);
			CreateOrderRequest request = new CreateOrderRequest(999L, 2);

			// when & then
			mockMvc
				.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
					.header(HttpHeaders.AUTHORIZATION, bearerToken(1L))
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isNotFound());
		}

		@Test
		@DisplayName("재고가 부족하면 409 Conflict를 반환한다")
		void insufficientStock() throws Exception {
			// given
			ProductResponse product = new ProductResponse(1L, "티셔츠", 29000L, 1);
			given(productClient.getProduct(1L)).willReturn(ApiResponse.success(product));
			CreateOrderRequest request = new CreateOrderRequest(1L, 10);

			// when & then
			mockMvc
				.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
					.header(HttpHeaders.AUTHORIZATION, bearerToken(1L))
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isConflict());
		}

	}

}
