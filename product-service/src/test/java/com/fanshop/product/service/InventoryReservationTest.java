package com.fanshop.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fanshop.ContextTest;
import com.fanshop.product.domain.Product;
import com.fanshop.product.domain.ProductRepository;
import com.fanshop.product.domain.ReservationRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * 상품의 집계 수량만으로는 상반된 이벤트의 순서를 판별할 수 없다.
 * <p>
 * 주문이 만료되면 {@code order.expired}가 예약을 해제하는데, 그 뒤에 {@code payment.completed}가 도착하면
 * product는 주문이 이미 만료된 것을 모른 채 확정을 시도한다. 그러면 예약량이 음수가 되고 팔지 않은 재고가 사라진다. 반대 순서에서도 늦게 온 만료
 * 이벤트가 확정된 예약을 다시 해제한다.
 * <p>
 * order-service는 이 경우를 {@code REFUND_REQUIRED}로 구분하지만 product-service에는 그 정보가 없다. 그래서 주문별
 * 예약 상태를 두고 전이가 가능할 때만 수량을 움직인다.
 */
@TestPropertySource(properties = { "outbox.relay.initial-delay=3600000", "outbox.relay.fixed-delay=3600000" })
class InventoryReservationTest extends ContextTest {

    private static final long ORDER_ID = 1001L;

    private final ProductService productService;

    private final ProductRepository productRepository;

    private final ReservationRepository reservationRepository;

    private Long productId;

    InventoryReservationTest(ProductService productService, ProductRepository productRepository,
            ReservationRepository reservationRepository) {
        this.productService = productService;
        this.productRepository = productRepository;
        this.reservationRepository = reservationRepository;
    }

    @BeforeEach
    void setUp() {
        productId = productRepository.save(new Product("한정판", 50000L, 100)).getId();
    }

    @AfterEach
    void tearDown() {
        reservationRepository.deleteAll();
        productRepository.deleteAll();
    }

    private Product reload() {
        return productRepository.findById(productId).orElseThrow();
    }

    @Test
    @DisplayName("예약 후 확정하면 재고가 줄고 예약량이 0으로 돌아온다")
    void reserveThenConfirm() {
        productService.softReserveStock(ORDER_ID, productId, 2);

        productService.confirmReservation(ORDER_ID, productId, 2);

        assertThat(reload().getStockQuantity()).isEqualTo(98);
        assertThat(reload().getReservedQuantity()).isZero();
    }

    @Test
    @DisplayName("예약 후 해제하면 재고는 그대로고 예약량만 0으로 돌아온다")
    void reserveThenRelease() {
        productService.softReserveStock(ORDER_ID, productId, 2);

        productService.releaseReservation(ORDER_ID, productId, 2);

        assertThat(reload().getStockQuantity()).isEqualTo(100);
        assertThat(reload().getReservedQuantity()).isZero();
    }

    @Test
    @DisplayName("만료로 해제된 뒤 결제 성공이 도착해도 확정하지 않는다 — 예약량이 음수가 되지 않는다")
    void lateCompletionAfterRelease() {
        productService.softReserveStock(ORDER_ID, productId, 2);
        productService.releaseReservation(ORDER_ID, productId, 2);

        productService.confirmReservation(ORDER_ID, productId, 2);

        assertThat(reload().getReservedQuantity()).isZero();
        assertThat(reload().getStockQuantity()).isEqualTo(100);
    }

    @Test
    @DisplayName("확정된 뒤 만료 이벤트가 도착해도 해제하지 않는다 — 팔린 재고가 되살아나지 않는다")
    void lateExpiryAfterConfirm() {
        productService.softReserveStock(ORDER_ID, productId, 2);
        productService.confirmReservation(ORDER_ID, productId, 2);

        productService.releaseReservation(ORDER_ID, productId, 2);

        assertThat(reload().getStockQuantity()).isEqualTo(98);
        assertThat(reload().getReservedQuantity()).isZero();
    }

    @Test
    @DisplayName("같은 주문의 확정이 두 번 와도 한 번만 반영된다")
    void confirmIsIdempotent() {
        productService.softReserveStock(ORDER_ID, productId, 2);

        productService.confirmReservation(ORDER_ID, productId, 2);
        productService.confirmReservation(ORDER_ID, productId, 2);

        assertThat(reload().getStockQuantity()).isEqualTo(98);
    }

}
