package com.fanshop.order.domain;

import com.fanshop.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "orders")
public class Order extends BaseEntity {

    @Column(nullable = false)
    private Long memberId;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private long totalPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    public Order(Long memberId, Long productId, int quantity, long totalPrice, OrderStatus status) {
        this.memberId = memberId;
        this.productId = productId;
        this.quantity = quantity;
        this.totalPrice = totalPrice;
        this.status = status;
    }

    public void waitForPayment() {
        this.status = OrderStatus.WAITING_PAYMENT;
    }

    /**
     * 결제 성공 수신. 만료된 뒤 도착한 경우 확정하지 않고 환불 대상으로 남긴다 — 예약 재고는 이미 해제되어 다른 주문에 팔렸을 수 있으므로 되잡지
     * 않는다.
     */
    public void onPaymentCompleted() {
        switch (status) {
            case PENDING, WAITING_PAYMENT -> this.status = OrderStatus.CONFIRMED;
            case EXPIRED -> this.status = OrderStatus.REFUND_REQUIRED;
            default -> throw new IllegalStateException("확정할 수 없는 상태 — status=" + status);
        }
    }

    public void cancel() {
        if (status != OrderStatus.PENDING && status != OrderStatus.WAITING_PAYMENT) {
            throw new IllegalStateException("취소할 수 없는 상태 — status=" + status);
        }
        this.status = OrderStatus.CANCELLED;
    }

    /** 결제 응답이 오지 않아 만료시킨다. 결제 대기 중인 주문만 대상이다. */
    public void expire() {
        if (status != OrderStatus.WAITING_PAYMENT) {
            throw new IllegalStateException("만료시킬 수 없는 상태 — status=" + status);
        }
        this.status = OrderStatus.EXPIRED;
    }

}
