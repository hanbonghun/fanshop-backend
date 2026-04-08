package com.fanshop.product.api;

import com.fanshop.product.domain.Product;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class CreateProductRequest {

	private String name;

	private long price;

	private int stockQuantity;

	public Product toEntity() {
		return new Product(name, price, stockQuantity);
	}

}
