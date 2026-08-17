package com.fanshop.payment.service;

import com.fanshop.outbox.OutboxRecorder;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 승인과 발행 예약을 한 트랜잭션으로 묶는다. 승인된 결제가 저장되었다면 Outbox 행도 반드시 존재하므로, 승인 직후 발행 전에 프로세스가 죽어도
 * 릴레이가 재발행한다.
 * <p>
 * 재고 예약 시점에 있던 이 책임을 승인 시점으로 옮긴 것이다. 결제가 실제로 일어나는 지점이 여기이기 때문이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentConfirmService {

    static final String PAYMENT_COMPLETED = "PAYMENT_COMPLETED";

    static final String PAYMENT_FAILED = "PAYMENT_FAILED";

    private final PaymentService paymentService;

    private final OutboxRecorder outboxRecorder;

    @Transactional
    public void confirm(Long orderId, String paymentKey, long amount) {
        switch (paymentService.confirm(orderId, paymentKey, amount)) {
            case PaymentResult.Approved(var completedEvent) -> outboxRecorder.record(PAYMENT_COMPLETED, completedEvent);
            case PaymentResult.Failed(var failedEvent) -> outboxRecorder.record(PAYMENT_FAILED, failedEvent);
            case PaymentResult.AlreadyProcessed() -> log.warn("이미 처리된 결제 — orderId={}", orderId);
        }
    }

}
