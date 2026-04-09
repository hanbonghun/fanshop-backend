package com.fanshop.product.api;

import com.fanshop.product.service.ProductService;
import com.fanshop.support.response.ApiResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {

	private final ProductService productService;

	@PostMapping(version = "1")
	public ApiResponse<ProductResponse> create(@RequestBody CreateProductRequest request) {
		return ApiResponse.success(productService.create(request));
	}

	@GetMapping(path = "/{productId}", version = "1")
	public ApiResponse<ProductResponse> getProduct(@PathVariable Long productId) {
		return ApiResponse.success(productService.getProduct(productId));
	}

	@PatchMapping(path = "/{productId}/stock", version = "1")
	public ApiResponse<Void> decreaseStock(@PathVariable Long productId, @RequestBody int quantity) {
		productService.decreaseStock(productId, quantity);
		return ApiResponse.success(null);
	}

}
