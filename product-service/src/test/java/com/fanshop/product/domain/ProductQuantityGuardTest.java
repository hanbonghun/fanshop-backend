package com.fanshop.product.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 수량은 API 검증만으로 막지 않는다. 이벤트로 들어오는 경로는 컨트롤러를 거치지 않으므로 도메인이 스스로 거절해야 한다.
 * <p>
 * 음수 수량은 예약량을 줄이고, 그 상태로 확정되면 {@code stockQuantity -= 음수}가 되어 팔지 않은 재고가 늘어난다.
 */
class ProductQuantityGuardTest {

    private Product product() {
        return new Product("상품", 10000L, 100);
    }

    @Nested
    @DisplayName("softReserve")
    class SoftReserve {

        @Test
        @DisplayName("0 이하 수량은 거절한다")
        void rejectsNonPositive() {
            assertThatThrownBy(() -> product().softReserve(-1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> product().softReserve(0)).isInstanceOf(IllegalArgumentException.class);
        }

    }

    @Nested
    @DisplayName("releaseReservation")
    class ReleaseReservation {

        @Test
        @DisplayName("예약한 적 없는 수량은 해제할 수 없다 — 예약량이 음수가 되지 않는다")
        void doesNotGoNegative() {
            Product product = product();

            assertThatThrownBy(() -> product.releaseReservation(1)).isInstanceOf(IllegalStateException.class);
            assertThat(product.getReservedQuantity()).isZero();
        }

    }

    @Nested
    @DisplayName("confirmReservation")
    class ConfirmReservation {

        @Test
        @DisplayName("예약량을 넘는 확정은 거절한다 — 만료로 이미 해제된 뒤 늦은 결제가 도착한 경우")
        void rejectsConfirmBeyondReserved() {
            Product product = product();
            product.softReserve(2);
            product.releaseReservation(2);

            assertThatThrownBy(() -> product.confirmReservation(2)).isInstanceOf(IllegalStateException.class);
            assertThat(product.getReservedQuantity()).isZero();
            assertThat(product.getStockQuantity()).isEqualTo(100);
        }

    }

}
