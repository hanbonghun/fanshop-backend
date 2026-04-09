package com.fanshop.client;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ProductResponse {
	private Long id;
	private String name;
	private long price;
	private int stockQuantity;
}
