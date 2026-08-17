package com.fanshop.payment.api;

/**
 * 구매자가 결제창에서 카드사 인증을 마치면 PG가 paymentKey를 발급한다. 그 값을 orderId, amount와 함께 되돌려받아 승인을 요청한다.
 */
public record ConfirmPaymentRequest(Long orderId, String paymentKey, long amount) {

}
