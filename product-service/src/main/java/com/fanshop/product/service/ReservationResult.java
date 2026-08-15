package com.fanshop.product.service;

/**
 * 재고 예약 결과. 재고 부족이나 상품 없음은 재시도해도 성공하지 않는 결정적 실패이므로 예외가 아닌 반환값으로 표현한다. 예외로 던지면 리스너 트랜잭션이
 * rollback-only가 되어 보상 이벤트(inventory.rejected)를 저장할 수 없다.
 */
public sealed interface ReservationResult permits ReservationResult.Reserved, ReservationResult.Rejected {

    record Reserved() implements ReservationResult {
    }

    record Rejected(String reason) implements ReservationResult {
    }

    static ReservationResult reserved() {
        return new Reserved();
    }

    static ReservationResult rejected(String reason) {
        return new Rejected(reason);
    }

}
