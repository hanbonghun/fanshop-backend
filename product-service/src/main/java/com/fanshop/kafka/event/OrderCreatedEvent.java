package com.fanshop.kafka.event;

public record OrderCreatedEvent(Long orderId, Long memberId, Long productId, int quantity) {

}
