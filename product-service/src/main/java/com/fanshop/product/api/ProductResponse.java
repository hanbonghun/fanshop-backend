package com.fanshop.product.api;

import com.fanshop.product.domain.Product;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ProductResponse {

	private Long id;

	private String name;

	private long price;

	private int stockQuantity;

	public static ProductResponse from(Product product) {
		return new ProductResponse(product.getId(), product.getName(), product.getPrice(),
				product.getStockQuantity());
	}

}
