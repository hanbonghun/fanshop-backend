package com.fanshop.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fanshop.messaging.event.InventoryReservedEvent;
import com.fanshop.payment.domain.Payment;
import com.fanshop.payment.domain.PaymentRepository;
import com.fanshop.payment.domain.PaymentStatus;
import com.fanshop.pg.PgPaymentResult;
import com.fanshop.pg.TossPaymentsClient;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private TossPaymentsClient tossPaymentsClient;

    @InjectMocks
    private PaymentService paymentService;

    private final InventoryReservedEvent event = new InventoryReservedEvent(1L, 2L, 3L, 1, 50000L);

    @Nested
    @DisplayName("processPayment (결제 처리)")
    class ProcessPayment {

        @Test
        @DisplayName("PG 승인 성공 시 Payment를 APPROVED로 저장하고 Approved 결과를 반환한다")
        void pgSuccess() {
            // given
            Payment payment = new Payment(event.orderId(), event.memberId(), event.totalPrice());
            given(paymentRepository.existsByOrderId(event.orderId())).willReturn(false);
            given(paymentRepository.save(any())).willReturn(payment);
            given(tossPaymentsClient.pay(any())).willReturn(PgPaymentResult.success());

            // when
            PaymentResult result = paymentService.processPayment(event);

            // then
            assertThat(result).isInstanceOf(PaymentResult.Approved.class);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        }

        @Test
        @DisplayName("PG 승인 실패 시 Payment를 FAILED로 저장하고 Failed 결과를 반환한다")
        void pgFailure() {
            // given
            Payment payment = new Payment(event.orderId(), event.memberId(), event.totalPrice());
            given(paymentRepository.existsByOrderId(event.orderId())).willReturn(false);
            given(paymentRepository.save(any())).willReturn(payment);
            given(tossPaymentsClient.pay(any())).willReturn(PgPaymentResult.failure("잔액 부족"));

            // when
            PaymentResult result = paymentService.processPayment(event);

            // then
            assertThat(result).isInstanceOf(PaymentResult.Failed.class);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        }

        @Test
        @DisplayName("이미 처리된 orderId면 PG 호출 없이 AlreadyProcessed를 반환한다")
        void idempotent() {
            // given
            given(paymentRepository.existsByOrderId(event.orderId())).willReturn(true);

            // when
            PaymentResult result = paymentService.processPayment(event);

            // then
            assertThat(result).isInstanceOf(PaymentResult.AlreadyProcessed.class);
            verify(tossPaymentsClient, never()).pay(any());
        }

    }

}
