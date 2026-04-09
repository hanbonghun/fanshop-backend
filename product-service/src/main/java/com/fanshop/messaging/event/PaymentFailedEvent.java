package com.fanshop.messaging.event;

public record PaymentFailedEvent(Long orderId, Long memberId, Long productId, int quantity, String reason) {

}
