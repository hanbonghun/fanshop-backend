package com.fanshop.client;

import com.fanshop.support.response.ApiResponse;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PatchExchange;

@HttpExchange("/api/v1/products")
public interface ProductClient {

	@GetExchange("/{productId}")
	ApiResponse<ProductResponse> getProduct(@PathVariable("productId") Long productId);

	@PatchExchange("/{productId}/stock")
	ApiResponse<Void> decreaseStock(@PathVariable("productId") Long productId, @RequestBody int quantity);

}
