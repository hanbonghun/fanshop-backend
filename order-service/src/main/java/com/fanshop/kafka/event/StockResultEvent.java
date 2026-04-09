package com.fanshop.kafka.event;

public record StockResultEvent(Long orderId, boolean success, String reason) {

}
