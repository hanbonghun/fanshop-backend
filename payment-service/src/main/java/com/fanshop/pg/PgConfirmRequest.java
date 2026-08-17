package com.fanshop.pg;

/**
 * PG 승인 요청. 구매자 인증이 끝나야 발급되는 paymentKey가 있어야 성립하므로, 서버가 단독으로 만들어낼 수 없다.
 */
public record PgConfirmRequest(String paymentKey, Long orderId, long amount) {

}
