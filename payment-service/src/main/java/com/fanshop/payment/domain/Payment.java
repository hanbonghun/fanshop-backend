package com.fanshop.payment.domain;

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
@Table(name = "payments")
public class Payment extends BaseEntity {

    @Column(nullable = false, unique = true)
    private Long orderId;

    @Column(nullable = false)
    private Long memberId;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    /**
     * 재고 예약 시점에 확정해 저장한다. 승인 요청으로 들어온 금액이 이 값과 다르면 승인하지 않는다. 클라이언트가 결제 금액을 조작하는 경로를 막는
     * 기준값이다.
     */
    @Column(nullable = false)
    private long amount;

    /**
     * PG가 인증 완료 시 발급하는 결제 식별자. 승인·조회·취소에 모두 필요해 승인 시점에 저장한다.
     */
    @Column
    private String paymentKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    public Payment(Long orderId, Long memberId, Long productId, int quantity, long amount) {
        this.orderId = orderId;
        this.memberId = memberId;
        this.productId = productId;
        this.quantity = quantity;
        this.amount = amount;
        this.status = PaymentStatus.PENDING;
    }

    public void approve(String paymentKey) {
        this.paymentKey = paymentKey;
        this.status = PaymentStatus.APPROVED;
    }

    public void fail() {
        this.status = PaymentStatus.FAILED;
    }

}
