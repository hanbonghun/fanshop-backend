package com.fanshop.product.domain;

import com.fanshop.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문 하나가 잡은 예약. 상품의 집계 수량({@code reservedQuantity})만으로는 상반된 이벤트의 순서를 판별할 수 없어 도입했다.
 *
 * <p>
 * 만료로 해제된 뒤 결제 성공이 도착하거나, 확정된 뒤 늦은 만료가 도착하는 경우가 있다. 어느 쪽이든 집계 수량만 보면 조건이 같아 보여서 그대로 반영되고,
 * 예약량이 음수가 되거나 팔린 재고가 되살아난다.
 *
 * <p>
 * 전이가 가능할 때만 수량을 움직이고, 아니면 아무것도 하지 않는다. 이벤트가 어떤 순서로 오든 결과가 같아진다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "inventory_reservations")
public class InventoryReservation extends BaseEntity {

    @Column(nullable = false, unique = true)
    private Long orderId;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;

    public InventoryReservation(Long orderId, Long productId, int quantity) {
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
        this.status = ReservationStatus.RESERVED;
    }

    /** 예약 중일 때만 확정으로 넘어간다. 이미 해제됐거나 확정됐으면 {@code false}. */
    public boolean confirm() {
        if (status != ReservationStatus.RESERVED) {
            return false;
        }
        this.status = ReservationStatus.CONFIRMED;
        return true;
    }

    /** 예약 중일 때만 해제로 넘어간다. 이미 확정됐거나 해제됐으면 {@code false}. */
    public boolean release() {
        if (status != ReservationStatus.RESERVED) {
            return false;
        }
        this.status = ReservationStatus.RELEASED;
        return true;
    }

}
