package com.fanshop.order.api;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CreateOrderRequest {
	private Long productId;
	private int quantity;
}
