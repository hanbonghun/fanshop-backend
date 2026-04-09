package com.fanshop.order.service;

import com.fanshop.client.ProductClient;
import com.fanshop.client.ProductResponse;
import com.fanshop.order.api.CreateOrderRequest;
import com.fanshop.order.api.OrderResponse;
import com.fanshop.order.domain.Order;
import com.fanshop.order.domain.OrderRepository;
import com.fanshop.order.domain.OrderStatus;
import com.fanshop.support.error.CoreException;
import com.fanshop.support.error.ErrorType;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

	private final OrderRepository orderRepository;

	private final ProductClient productClient;

	@Transactional
	public OrderResponse createOrder(Long memberId, CreateOrderRequest request) {
		// 1. 상품 조회
		ProductResponse product = fetchProduct(request.getProductId());

		// 2. 재고 확인
		if (product.getStockQuantity() < request.getQuantity()) {
			throw new CoreException(ErrorType.INSUFFICIENT_STOCK, request.getProductId());
		}

		// 3. 주문 저장
		long totalPrice = product.getPrice() * request.getQuantity();
		Order savedOrder = orderRepository
			.save(new Order(memberId, product.getId(), request.getQuantity(), totalPrice, OrderStatus.CONFIRMED));

		// 4. 재고 감소 — 동기 호출. 실패 시 트랜잭션 롤백
		productClient.decreaseStock(product.getId(), request.getQuantity());

		return OrderResponse.from(savedOrder);
	}

	private ProductResponse fetchProduct(Long productId) {
		try {
			return productClient.getProduct(productId).getData();
		}
		catch (HttpClientErrorException.NotFound e) {
			throw new CoreException(ErrorType.PRODUCT_NOT_FOUND, productId);
		}
	}

}
