package com.fanshop.product.service;

import com.fanshop.product.api.CreateProductRequest;
import com.fanshop.product.api.ProductResponse;
import com.fanshop.product.domain.Product;
import com.fanshop.product.domain.ProductRepository;
import com.fanshop.support.error.CoreException;
import com.fanshop.support.error.ErrorType;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

	private final ProductRepository productRepository;

	@Transactional
	public ProductResponse create(CreateProductRequest request) {
		Product saved = productRepository.save(request.toEntity());
		return ProductResponse.from(saved);
	}

	public ProductResponse getProduct(Long productId) {
		Product product = productRepository.findById(productId)
			.orElseThrow(() -> new CoreException(ErrorType.PRODUCT_NOT_FOUND, productId));
		return ProductResponse.from(product);
	}

	@Transactional
	public void decreaseStock(Long productId, int quantity) {
		Product product = productRepository.findById(productId)
			.orElseThrow(() -> new CoreException(ErrorType.PRODUCT_NOT_FOUND, productId));
		if (product.getStockQuantity() < quantity) {
			throw new CoreException(ErrorType.INSUFFICIENT_STOCK, productId);
		}
		product.decreaseStock(quantity);
	}

}
