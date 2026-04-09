package com.fanshop.messaging.event;

public record InventoryReservedEvent(Long orderId, Long memberId, Long productId, int quantity, long totalPrice) {

}
