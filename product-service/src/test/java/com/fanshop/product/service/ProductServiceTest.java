package com.fanshop.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.fanshop.product.api.CreateProductRequest;
import com.fanshop.product.api.ProductResponse;
import com.fanshop.product.domain.Product;
import com.fanshop.product.domain.ProductRepository;
import com.fanshop.support.error.CoreException;
import com.fanshop.support.error.ErrorType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductService productService;

    @Nested
    @DisplayName("create (상품 등록)")
    class Create {

        @Test
        @DisplayName("유효한 요청이면 상품을 저장하고 응답을 반환한다")
        void success() {
            // given
            CreateProductRequest request = new CreateProductRequest("티셔츠", 29000L, 100);
            Product product = new Product("티셔츠", 29000L, 100);
            given(productRepository.save(any(Product.class))).willReturn(product);

            // when
            ProductResponse response = productService.create(request);

            // then
            assertThat(response.getName()).isEqualTo("티셔츠");
            assertThat(response.getPrice()).isEqualTo(29000L);
            verify(productRepository).save(any(Product.class));
        }

    }

    @Nested
    @DisplayName("getProduct (상품 조회)")
    class GetProduct {

        @Test
        @DisplayName("존재하는 상품 ID면 상품 정보를 반환한다")
        void success() {
            // given
            Long productId = 1L;
            Product product = new Product("티셔츠", 29000L, 100);
            given(productRepository.findById(productId)).willReturn(Optional.of(product));

            // when
            ProductResponse response = productService.getProduct(productId);

            // then
            assertThat(response.getName()).isEqualTo("티셔츠");
        }

        @Test
        @DisplayName("존재하지 않는 상품 ID면 PRODUCT_NOT_FOUND 예외를 던진다")
        void notFound() {
            // given
            given(productRepository.findById(999L)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> productService.getProduct(999L)).isInstanceOf(CoreException.class)
                .satisfies(e -> assertThat(((CoreException) e).getErrorType()).isEqualTo(ErrorType.PRODUCT_NOT_FOUND));
        }

    }

    @Nested
    @DisplayName("decreaseStock (재고 감소)")
    class DecreaseStock {

        @Test
        @DisplayName("재고가 충분하면 재고를 감소시킨다")
        void success() {
            // given
            Long productId = 1L;
            Product product = new Product("티셔츠", 29000L, 100);
            given(productRepository.findByIdWithLock(productId)).willReturn(Optional.of(product));

            // when
            productService.decreaseStock(productId, 10);

            // then
            assertThat(product.getStockQuantity()).isEqualTo(90);
        }

        @Test
        @DisplayName("존재하지 않는 상품 ID면 PRODUCT_NOT_FOUND 예외를 던진다")
        void notFound() {
            // given
            given(productRepository.findByIdWithLock(999L)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> productService.decreaseStock(999L, 1)).isInstanceOf(CoreException.class)
                .satisfies(e -> assertThat(((CoreException) e).getErrorType()).isEqualTo(ErrorType.PRODUCT_NOT_FOUND));
        }

        @Test
        @DisplayName("재고가 부족하면 INSUFFICIENT_STOCK 예외를 던진다")
        void insufficientStock() {
            // given
            Long productId = 1L;
            Product product = new Product("티셔츠", 29000L, 5);
            given(productRepository.findByIdWithLock(productId)).willReturn(Optional.of(product));

            // when & then
            assertThatThrownBy(() -> productService.decreaseStock(productId, 10)).isInstanceOf(CoreException.class)
                .satisfies(e -> assertThat(((CoreException) e).getErrorType()).isEqualTo(ErrorType.INSUFFICIENT_STOCK));
        }

    }

}
