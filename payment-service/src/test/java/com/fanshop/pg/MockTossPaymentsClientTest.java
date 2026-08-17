package com.fanshop.pg;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Mock PG가 항상 승인하면 {@code payment.failed → CANCELLED} 보상 경로를 종단으로 확인할 수 없다. 단위 테스트는
 * Mockito로 거절을 만들 수 있지만, 실제 프로세스를 띄우고 도는 k6에는 그 수단이 없다.
 * <p>
 * 승인 여부를 호출자가 정하도록 {@code paymentKey} 접두사로 트리거를 뒀다. 실제 PG 샌드박스가 테스트용 카드번호로 실패를 재현하게 하는 것과
 * 같은 방식이며, 무작위 실패율보다 결정적이라 재현이 쉽다.
 */
class MockTossPaymentsClientTest {

    private final MockTossPaymentsClient client = new MockTossPaymentsClient();

    @Test
    @DisplayName("일반 paymentKey는 승인한다")
    void approvesNormalKey() {
        PgPaymentResult result = client.confirm(new PgConfirmRequest("pay_key_1", 1L, 50000L));

        assertThat(result.approved()).isTrue();
    }

    @Test
    @DisplayName("fail_ 로 시작하는 paymentKey는 거절한다 — 보상 경로를 종단으로 재현하기 위한 트리거")
    void rejectsFailPrefixedKey() {
        PgPaymentResult result = client.confirm(new PgConfirmRequest("fail_key_1", 1L, 50000L));

        assertThat(result.approved()).isFalse();
        assertThat(result.failureReason()).isNotBlank();
    }

}
