package com.fanshop.pg;

public record PgPaymentResult(boolean approved, String failureReason) {

    public static PgPaymentResult success() {
        return new PgPaymentResult(true, null);
    }

    public static PgPaymentResult failure(String reason) {
        return new PgPaymentResult(false, reason);
    }

}
