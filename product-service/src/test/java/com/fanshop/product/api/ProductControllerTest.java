package com.fanshop.product.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fanshop.ContextTest;
import com.fanshop.messaging.StockEventPublisher;
import com.fanshop.product.domain.Product;
import com.fanshop.product.domain.ProductRepository;

import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ProductControllerTest extends ContextTest {

    private final MockMvc mockMvc;

    private final ObjectMapper objectMapper;

    private final ProductRepository productRepository;

    @MockitoBean
    private StockEventPublisher stockEventPublisher;

    ProductControllerTest(MockMvc mockMvc, ObjectMapper objectMapper, ProductRepository productRepository) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.productRepository = productRepository;
    }

    @AfterEach
    void tearDown() {
        productRepository.deleteAll();
    }

    @Nested
    @DisplayName("POST /api/v1/products")
    class Create {

        @Test
        @DisplayName("유효한 요청이면 200 OK와 상품 정보를 반환하고 DB에 저장된다")
        void success() throws Exception {
            // given
            CreateProductRequest request = new CreateProductRequest("티셔츠", 29000L, 100);

            // when & then
            mockMvc
                .perform(post("/api/v1/products").contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("티셔츠"))
                .andExpect(jsonPath("$.data.price").value(29000))
                .andExpect(jsonPath("$.data.stockQuantity").value(100));

            assertThat(productRepository.count()).isEqualTo(1);
        }

    }

    @Nested
    @DisplayName("GET /api/v1/products/{productId}")
    class GetProduct {

        @Test
        @DisplayName("존재하는 상품 ID면 200 OK와 상품 정보를 반환한다")
        void success() throws Exception {
            // given
            Product saved = productRepository.save(new Product("후드티", 59000L, 50));

            // when & then
            mockMvc.perform(get("/api/v1/products/{productId}", saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("후드티"))
                .andExpect(jsonPath("$.data.stockQuantity").value(50));
        }

        @Test
        @DisplayName("존재하지 않는 상품 ID면 404 Not Found를 반환한다")
        void notFound() throws Exception {
            mockMvc.perform(get("/api/v1/products/{productId}", 999L)).andExpect(status().isNotFound());
        }

    }

    @Nested
    @DisplayName("PATCH /api/v1/products/{productId}/stock")
    class DecreaseStock {

        @Test
        @DisplayName("재고가 충분하면 200 OK를 반환하고 재고가 감소된다")
        void success() throws Exception {
            // given
            Product saved = productRepository.save(new Product("티셔츠", 29000L, 100));

            // when & then
            mockMvc
                .perform(patch("/api/v1/products/{productId}/stock", saved.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("10"))
                .andExpect(status().isOk());

            Product updated = productRepository.findById(saved.getId()).get();
            assertThat(updated.getStockQuantity()).isEqualTo(90);
        }

        @Test
        @DisplayName("재고가 부족하면 409 Conflict를 반환한다")
        void insufficientStock() throws Exception {
            // given
            Product saved = productRepository.save(new Product("티셔츠", 29000L, 5));

            // when & then
            mockMvc
                .perform(patch("/api/v1/products/{productId}/stock", saved.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("10"))
                .andExpect(status().isConflict());
        }

    }

}
