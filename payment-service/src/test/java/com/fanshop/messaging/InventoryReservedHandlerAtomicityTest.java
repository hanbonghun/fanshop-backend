package com.fanshop.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

import com.fanshop.ContextTest;
import com.fanshop.messaging.event.InventoryReservedEvent;
import com.fanshop.messaging.event.PaymentCompletedEvent;
import com.fanshop.outbox.OutboxEventRepository;
import com.fanshop.outbox.OutboxRecorder;
import com.fanshop.payment.domain.PaymentRepository;
import com.fanshop.payment.domain.PaymentStatus;
import com.fanshop.pg.PgPaymentResult;
import com.fanshop.pg.TossPaymentsClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@TestPropertySource(properties = { "outbox.relay.initial-delay=3600000", "outbox.relay.fixed-delay=3600000" })
class InventoryReservedHandlerAtomicityTest extends ContextTest {

    private final InventoryReservedHandler handler;

    private final PaymentRepository paymentRepository;

    private final OutboxEventRepository outboxEventRepository;

    @MockitoBean
    private TossPaymentsClient tossPaymentsClient;

    // 실제 OutboxRecorder를 감싸는 스파이 — 다른 테스트는 실제 저장 동작을 그대로 타되, 이 테스트에서만
    // record() 호출 시점에 예외를 주입해 "결제 승인 이후, 발행 예약 직전" 실패 시나리오를 재현한다.
    @MockitoSpyBean
    private OutboxRecorder outboxRecorder;

    InventoryReservedHandlerAtomicityTest(InventoryReservedHandler handler, PaymentRepository paymentRepository,
            OutboxEventRepository outboxEventRepository) {
        this.handler = handler;
        this.paymentRepository = paymentRepository;
        this.outboxEventRepository = outboxEventRepository;
    }

    @AfterEach
    void tearDown() {
        paymentRepository.deleteAll();
        outboxEventRepository.deleteAll();
    }

    @Test
    @DisplayName("결제 저장과 발행 예약이 함께 커밋된다 — 결제만 되고 이벤트가 유실되는 경로가 사라진다")
    void paymentAndOutboxAreAtomic() {
        given(tossPaymentsClient.pay(any())).willReturn(PgPaymentResult.success());

        handler.handle(new InventoryReservedEvent(1L, 2L, 3L, 4, 50000L));

        assertThat(paymentRepository.existsByOrderId(1L)).isTrue();
        assertThat(outboxEventRepository.findAll()).singleElement()
            .satisfies(e -> assertThat(e.getEventType()).isEqualTo("PAYMENT_COMPLETED"));
    }

    @Test
    @DisplayName("이미 처리된 결제는 중복 저장하지 않는다")
    void skipsAlreadyProcessed() {
        given(tossPaymentsClient.pay(any())).willReturn(PgPaymentResult.success());
        InventoryReservedEvent event = new InventoryReservedEvent(1L, 2L, 3L, 4, 50000L);
        handler.handle(event);
        outboxEventRepository.deleteAll();

        handler.handle(event);

        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    @DisplayName("결제 중 일시 실패 시 Payment와 Outbox가 함께 롤백된다")
    void rollsBackTogetherOnTransientFailure() {
        given(tossPaymentsClient.pay(any())).willThrow(new RuntimeException("PG 연동 일시 오류"));
        InventoryReservedEvent event = new InventoryReservedEvent(1L, 2L, 3L, 4, 50000L);

        assertThatThrownBy(() -> handler.handle(event)).isInstanceOf(RuntimeException.class);

        assertThat(paymentRepository.existsByOrderId(1L)).isFalse();
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    @DisplayName("결제 승인 후 발행 예약 직전 실패 시 결제도 함께 롤백된다 — 결제와 발행 예약이 한 트랜잭션임을 증명한다")
    void rollsBackPaymentWhenOutboxRecordFails() {
        given(tossPaymentsClient.pay(any())).willReturn(PgPaymentResult.success());
        willThrow(new RuntimeException("Outbox 기록 실패")).given(outboxRecorder)
            .record(eq("PAYMENT_COMPLETED"), any(PaymentCompletedEvent.class));
        InventoryReservedEvent event = new InventoryReservedEvent(1L, 2L, 3L, 4, 50000L);

        assertThatThrownBy(() -> handler.handle(event)).isInstanceOf(RuntimeException.class);

        assertThat(paymentRepository.existsByOrderId(1L)).isFalse();
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    @DisplayName("PG 거절은 롤백 없이 Payment(FAILED)와 PAYMENT_FAILED가 함께 커밋된다")
    void recordsFailedWithoutRollback() {
        given(tossPaymentsClient.pay(any())).willReturn(PgPaymentResult.failure("잔액 부족"));
        InventoryReservedEvent event = new InventoryReservedEvent(1L, 2L, 3L, 4, 50000L);

        handler.handle(event);

        assertThat(paymentRepository.findAll()).singleElement()
            .satisfies(p -> assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED));
        assertThat(outboxEventRepository.findAll()).singleElement()
            .satisfies(e -> assertThat(e.getEventType()).isEqualTo("PAYMENT_FAILED"));
    }

}
