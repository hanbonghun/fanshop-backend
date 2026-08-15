package com.fanshop.messaging;

import com.fanshop.messaging.event.InventoryReservedEvent;
import com.fanshop.outbox.OutboxRecorder;
import com.fanshop.payment.service.PaymentResult;
import com.fanshop.payment.service.PaymentService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 처리와 발행 예약을 한 트랜잭션으로 묶는다. 결제가 저장되었다면 Outbox 행도 반드시 존재하므로, 결제 승인 후 발행 직전에 프로세스가 죽어도
 * 릴레이가 재발행한다. AlreadyProcessed 분기가 안전해지는 근거다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryReservedHandler {

    static final String PAYMENT_COMPLETED = "PAYMENT_COMPLETED";

    static final String PAYMENT_FAILED = "PAYMENT_FAILED";

    private final PaymentService paymentService;

    private final OutboxRecorder outboxRecorder;

    @Transactional
    public void handle(InventoryReservedEvent event) {
        log.info("Received inventory.reserved — orderId={}", event.orderId());

        switch (paymentService.processPayment(event)) {
            case PaymentResult.Approved(var completedEvent) -> outboxRecorder.record(PAYMENT_COMPLETED, completedEvent);
            case PaymentResult.Failed(var failedEvent) -> outboxRecorder.record(PAYMENT_FAILED, failedEvent);
            case PaymentResult.AlreadyProcessed() -> log.warn("이미 처리된 결제 — orderId={}", event.orderId());
        }
    }

}
