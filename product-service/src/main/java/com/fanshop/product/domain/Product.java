package com.fanshop.product.domain;

import com.fanshop.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "products")
public class Product extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private long price;

    @Column(nullable = false)
    private int stockQuantity;

    @Column(nullable = false)
    private int reservedQuantity;

    public Product(String name, long price, int stockQuantity) {
        this.name = name;
        this.price = price;
        this.stockQuantity = stockQuantity;
        this.reservedQuantity = 0;
    }

    public int availableQuantity() {
        return stockQuantity - reservedQuantity;
    }

    /**
     * 수량 가드는 API 검증과 별개로 도메인에도 둔다. 이벤트로 들어오는 경로는 컨트롤러를 거치지 않기 때문이다. 음수 수량은 예약량을 줄이고, 그
     * 상태로 확정되면 팔지 않은 재고가 늘어난다.
     */
    private static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("수량은 1 이상이어야 합니다 — quantity=" + quantity);
        }
    }

    public void softReserve(int quantity) {
        requirePositive(quantity);
        if (availableQuantity() < quantity) {
            throw new IllegalStateException("재고가 부족합니다.");
        }
        this.reservedQuantity += quantity;
    }

    /**
     * 예약량을 넘는 확정은 거절한다. 만료로 이미 해제된 뒤 늦은 결제가 도착하는 경우가 여기 걸린다. 순서 판별 자체는
     * {@code InventoryReservation}이 하고, 이 가드는 그것을 통과한 뒤에도 수량이 음수로 내려가지 않게 하는 마지막 방어선이다.
     */
    public void confirmReservation(int quantity) {
        requirePositive(quantity);
        if (this.reservedQuantity < quantity) {
            throw new IllegalStateException(
                    "예약량을 넘는 확정 — reserved=" + this.reservedQuantity + ", quantity=" + quantity);
        }
        this.stockQuantity -= quantity;
        this.reservedQuantity -= quantity;
    }

    public void releaseReservation(int quantity) {
        requirePositive(quantity);
        if (this.reservedQuantity < quantity) {
            throw new IllegalStateException(
                    "예약량을 넘는 해제 — reserved=" + this.reservedQuantity + ", quantity=" + quantity);
        }
        this.reservedQuantity -= quantity;
    }

    // HTTP 직접 호출용 (관리자 재고 조정 등)
    public void decreaseStock(int quantity) {
        requirePositive(quantity);
        if (this.stockQuantity < quantity) {
            throw new IllegalStateException("재고가 부족합니다.");
        }
        this.stockQuantity -= quantity;
    }

}
