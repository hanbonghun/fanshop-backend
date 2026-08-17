package com.fanshop.pg;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

/**
 * 실제 PG 연동 전 Mock. 연동 흐름은 토스페이먼츠 v2 규격을 따르지만 카드사 승인은 일어나지 않는다.
 * <p>
 * 항상 승인하면 {@code payment.failed → CANCELLED} 보상 경로를 종단으로 확인할 수 없다. 단위 테스트는 Mockito로 거절을
 * 만들 수 있지만 실제 프로세스를 띄우고 도는 부하 테스트에는 그 수단이 없다. 그래서 승인 여부를 호출자가 정할 수 있도록 {@code paymentKey}
 * 접두사를 트리거로 뒀다. 실제 PG 샌드박스가 테스트용 카드번호로 실패를 재현하게 하는 것과 같은 방식이고, 무작위 실패율보다 결정적이라 재현이 쉽다.
 */
@Slf4j
@Component
public class MockTossPaymentsClient implements TossPaymentsClient {

    static final String FAILURE_PREFIX = "fail_";

    @Override
    public PgPaymentResult confirm(PgConfirmRequest request) {
        log.info("[MockPG] 승인 요청 — paymentKey={}, orderId={}, amount={}", request.paymentKey(), request.orderId(),
                request.amount());

        if (request.paymentKey() != null && request.paymentKey().startsWith(FAILURE_PREFIX)) {
            log.info("[MockPG] 거절 — 실패 트리거 접두사, orderId={}", request.orderId());
            return PgPaymentResult.failure("Mock 거절 — paymentKey가 '" + FAILURE_PREFIX + "'로 시작합니다.");
        }
        return PgPaymentResult.success();
    }

}
