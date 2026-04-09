package com.fanshop.payment.service;

import com.fanshop.messaging.event.InventoryReservedEvent;
import com.fanshop.messaging.event.PaymentCompletedEvent;
import com.fanshop.messaging.event.PaymentFailedEvent;
import com.fanshop.payment.domain.Payment;
import com.fanshop.payment.domain.PaymentRepository;
import com.fanshop.pg.PgPaymentRequest;
import com.fanshop.pg.PgPaymentResult;
import com.fanshop.pg.TossPaymentsClient;

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

    @Transactional
    public PaymentResult processPayment(InventoryReservedEvent event) {
        if (paymentRepository.existsByOrderId(event.orderId())) {
            log.warn("중복 결제 요청 무시 — orderId={}", event.orderId());
            return PaymentResult.alreadyProcessed();
        }

        Payment payment = paymentRepository.save(
                new Payment(event.orderId(), event.memberId(), event.totalPrice()));

        PgPaymentResult pgResult = tossPaymentsClient
            .pay(new PgPaymentRequest(event.orderId(), event.memberId(), event.totalPrice()));

        if (pgResult.approved()) {
            payment.approve();
            log.info("결제 승인 — orderId={}, amount={}", event.orderId(), event.totalPrice());
            return PaymentResult.approved(
                    new PaymentCompletedEvent(event.orderId(), event.memberId(), event.productId(), event.quantity()));
        }

        payment.fail();
        log.warn("결제 실패 — orderId={}, reason={}", event.orderId(), pgResult.failureReason());
        return PaymentResult.failed(new PaymentFailedEvent(event.orderId(), event.memberId(), event.productId(),
                event.quantity(), pgResult.failureReason()));
    }

}
