package com.fanshop.messaging.event;

public record InventoryRejectedEvent(Long orderId, String reason) {

}
