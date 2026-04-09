package com.fanshop.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.fanshop.ContextTest;
import com.fanshop.messaging.StockEventPublisher;
import com.fanshop.product.domain.Product;
import com.fanshop.product.domain.ProductRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class ProductStockConcurrencyTest extends ContextTest {

    private final ProductService productService;

    private final ProductRepository productRepository;

    @MockitoBean
    private StockEventPublisher stockEventPublisher;

    ProductStockConcurrencyTest(ProductService productService, ProductRepository productRepository) {
        this.productService = productService;
        this.productRepository = productRepository;
    }

    @AfterEach
    void tearDown() {
        productRepository.deleteAll();
    }

    @Test
    @DisplayName("재고 100개 상품에 100건 동시 요청 시 재고가 0이어야 한다 — 락 없으면 유실 발생")
    void stockDecreaseShouldBeZeroAfter100ConcurrentRequests() throws InterruptedException {
        // given
        int threadCount = 100;
        Product product = productRepository.save(new Product("한정판 MD", 50000L, threadCount));
        Long productId = product.getId();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    productService.decreaseStock(productId, 1);
                    successCount.incrementAndGet();
                }
                catch (Exception e) {
                    failCount.incrementAndGet();
                }
                finally {
                    doneLatch.countDown();
                }
            });
        }

        // when: 100개 스레드 동시 시작
        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // then
        Product result = productRepository.findById(productId).orElseThrow();
        int finalStock = result.getStockQuantity();

        System.out.printf("[동시성 테스트] 성공: %d건 / 실패: %d건 / 최종 재고: %d (기대값: 0)%n", successCount.get(), failCount.get(),
                finalStock);

        // 락이 없으면 이 assertion은 실패한다.
        // 여러 트랜잭션이 같은 재고를 동시에 읽고 덮어쓰는 Lost Update 발생.
        // 비관적 락(pessimistic lock) 적용 후 이 테스트가 통과해야 한다.
        assertThat(finalStock).isZero();
    }

}
