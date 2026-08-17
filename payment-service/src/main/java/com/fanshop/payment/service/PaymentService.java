package com.fanshop.payment.service;

import com.fanshop.messaging.event.InventoryReservedEvent;
import com.fanshop.messaging.event.PaymentCompletedEvent;
import com.fanshop.messaging.event.PaymentFailedEvent;
import com.fanshop.payment.domain.Payment;
import com.fanshop.payment.domain.PaymentRepository;
import com.fanshop.payment.domain.PaymentStatus;
import com.fanshop.pg.PgConfirmRequest;
import com.fanshop.pg.PgPaymentResult;
import com.fanshop.pg.TossPaymentsClient;
import com.fanshop.support.error.CoreException;
import com.fanshop.support.error.ErrorType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentService {

    private final PaymentRepository paymentRepository;

    private final TossPaymentsClient tossPaymentsClient;

    /**
     * 재고가 예약되면 결제 대기를 만든다. 승인 금액의 기준값을 이 시점에 확정해 저장하는 것이 목적이며, PG는 호출하지 않는다.
     */
    @Transactional
    public void prepare(InventoryReservedEvent event) {
        if (paymentRepository.existsByOrderId(event.orderId())) {
            log.warn("이미 준비된 결제 — orderId={}", event.orderId());
            return;
        }
        paymentRepository.save(new Payment(event.orderId(), event.memberId(), event.productId(), event.quantity(),
                event.totalPrice()));
    }

    @Transactional
    public PaymentResult confirm(Long orderId, String paymentKey, long amount) {
        Payment payment = paymentRepository.findByOrderId(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.PAYMENT_NOT_FOUND));
        if (payment.getStatus() != PaymentStatus.PENDING) {
            log.warn("이미 처리된 결제 — orderId={}, status={}", orderId, payment.getStatus());
            return PaymentResult.alreadyProcessed();
        }

        if (payment.getAmount() != amount) {
            throw new CoreException(ErrorType.PAYMENT_AMOUNT_MISMATCH);
        }

        PgPaymentResult pgResult = tossPaymentsClient.confirm(new PgConfirmRequest(paymentKey, orderId, amount));

        if (pgResult.approved()) {
            payment.approve(paymentKey);
            log.info("결제 승인 — orderId={}, amount={}", orderId, amount);
            return PaymentResult.approved(new PaymentCompletedEvent(orderId, payment.getMemberId(),
                    payment.getProductId(), payment.getQuantity()));
        }

        payment.fail();
        log.warn("결제 거절 — orderId={}, reason={}", orderId, pgResult.failureReason());
        return PaymentResult.failed(new PaymentFailedEvent(orderId, payment.getMemberId(), payment.getProductId(),
                payment.getQuantity(), pgResult.failureReason()));
    }

}
