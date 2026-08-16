package com.fanshop.messaging.event;

/** 결제 응답이 오지 않아 주문이 만료됐음을 알린다. product-service가 받아 예약 재고를 해제한다. */
public record OrderExpiredEvent(Long orderId, Long productId, int quantity) {

}
