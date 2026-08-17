package com.fanshop.pg;

public interface TossPaymentsClient {

    PgPaymentResult confirm(PgConfirmRequest request);

}
