package com.fanshop.messaging.event;

public record StockResultEvent(Long orderId, boolean success, String reason) {

}
