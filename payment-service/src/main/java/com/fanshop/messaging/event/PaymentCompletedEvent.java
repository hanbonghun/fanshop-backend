package com.fanshop.messaging.event;

public record PaymentCompletedEvent(Long orderId, Long memberId, Long productId, int quantity) {

}
