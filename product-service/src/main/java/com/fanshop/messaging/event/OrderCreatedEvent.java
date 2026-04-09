package com.fanshop.messaging.event;

public record OrderCreatedEvent(Long orderId, Long memberId, Long productId, int quantity, long totalPrice) {

}
