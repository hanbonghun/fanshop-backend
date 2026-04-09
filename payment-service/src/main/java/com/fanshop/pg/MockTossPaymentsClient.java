package com.fanshop.pg;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MockTossPaymentsClient implements TossPaymentsClient {

    @Override
    public PgPaymentResult pay(PgPaymentRequest request) {
        log.info("[MockPG] 결제 요청 — orderId={}, amount={}", request.orderId(), request.amount());
        // 실제 PG 연동 전 Mock: 항상 성공 처리
        // 실패 시나리오 테스트는 테스트 코드에서 Mockito로 제어
        return PgPaymentResult.success();
    }

}
