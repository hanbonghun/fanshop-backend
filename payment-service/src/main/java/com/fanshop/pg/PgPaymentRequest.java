package com.fanshop.pg;

public record PgPaymentRequest(Long orderId, Long memberId, long amount) {

}
