package com.fanshop.order.domain;

public enum OrderStatus {

    PENDING, WAITING_PAYMENT, CONFIRMED, CANCELLED,

    /** 결제 응답이 오지 않아 스위퍼가 만료시킨 상태. 예약 재고는 해제된다. */
    EXPIRED,

    /** 만료 처리 후 결제 성공이 도착한 상태. 돈은 나갔고 재고는 이미 해제되어 운영 개입이 필요하다. */
    REFUND_REQUIRED

}
