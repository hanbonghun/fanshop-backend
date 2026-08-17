package com.fanshop.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    @DisplayName("prepare (결제 대기 생성)")
    class Prepare {

        @Test
        @DisplayName("PG를 호출하지 않고 PENDING 상태의 Payment를 저장한다")
        void savesPendingWithoutCallingPg() {
            // when
            paymentService.prepare(event);

            // then
            ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
            verify(paymentRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(PaymentStatus.PENDING);
            verify(tossPaymentsClient, never()).confirm(any());
        }

        @Test
        @DisplayName("이미 준비된 주문이면 Payment를 다시 저장하지 않는다")
        void skipsWhenAlreadyPrepared() {
            // given — at-least-once 전달로 inventory.reserved가 재수신된 상황
            given(paymentRepository.existsByOrderId(event.orderId())).willReturn(true);

            // when
            paymentService.prepare(event);

            // then
            verify(paymentRepository, never()).save(any());
        }

    }

    @Nested
    @DisplayName("confirm (결제 승인)")
    class Confirm {

        @Test
        @DisplayName("요청 금액이 결제 대기 금액과 다르면 PG를 호출하지 않고 거부한다")
        void rejectsAmountMismatch() {
            // given — 클라이언트가 결제 금액을 조작해 승인을 시도한 상황
            given(paymentRepository.findByOrderId(1L)).willReturn(Optional.of(new Payment(1L, 2L, 3L, 1, 50000L)));

            // when / then
            assertThatThrownBy(() -> paymentService.confirm(1L, "pay_key_1", 1000L)).isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.PAYMENT_AMOUNT_MISMATCH);
            verify(tossPaymentsClient, never()).confirm(any());
        }

        @Test
        @DisplayName("결제 대기가 없는 주문이면 PG를 호출하지 않고 거부한다")
        void rejectsUnknownOrder() {
            // given — 재고 예약이 끝나지 않았거나 존재하지 않는 orderId로 승인을 시도한 상황
            given(paymentRepository.findByOrderId(99L)).willReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> paymentService.confirm(99L, "pay_key_1", 50000L)).isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.PAYMENT_NOT_FOUND);
            verify(tossPaymentsClient, never()).confirm(any());
        }

        @Test
        @DisplayName("PG 승인 성공 시 APPROVED로 전이하고 paymentKey를 저장한 뒤 Approved를 반환한다")
        void approvesOnPgSuccess() {
            // given
            Payment payment = new Payment(1L, 2L, 3L, 4, 50000L);
            given(paymentRepository.findByOrderId(1L)).willReturn(Optional.of(payment));
            given(tossPaymentsClient.confirm(new PgConfirmRequest("pay_key_1", 1L, 50000L)))
                .willReturn(PgPaymentResult.success());

            // when
            PaymentResult result = paymentService.confirm(1L, "pay_key_1", 50000L);

            // then
            assertThat(result).isInstanceOf(PaymentResult.Approved.class);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
            assertThat(payment.getPaymentKey()).isEqualTo("pay_key_1");
            assertThat(((PaymentResult.Approved) result).event())
                .isEqualTo(new PaymentCompletedEvent(1L, 2L, 3L, 4));
        }

        @Test
        @DisplayName("PG가 거절하면 FAILED로 전이하고 보상 이벤트를 담은 Failed를 반환한다")
        void failsOnPgRejection() {
            // given
            Payment payment = new Payment(1L, 2L, 3L, 4, 50000L);
            given(paymentRepository.findByOrderId(1L)).willReturn(Optional.of(payment));
            given(tossPaymentsClient.confirm(any())).willReturn(PgPaymentResult.failure("잔액 부족"));

            // when
            PaymentResult result = paymentService.confirm(1L, "pay_key_1", 50000L);

            // then
            assertThat(result).isInstanceOf(PaymentResult.Failed.class);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(((PaymentResult.Failed) result).event())
                .isEqualTo(new PaymentFailedEvent(1L, 2L, 3L, 4, "잔액 부족"));
        }

        @Test
        @DisplayName("이미 승인된 결제에 승인이 다시 오면 PG를 호출하지 않고 AlreadyProcessed를 반환한다")
        void idempotentOnRetry() {
            // given — 성공 리다이렉트가 중복 도달하거나 사용자가 새로고침한 상황
            Payment payment = new Payment(1L, 2L, 3L, 4, 50000L);
            payment.approve("pay_key_1");
            given(paymentRepository.findByOrderId(1L)).willReturn(Optional.of(payment));

            // when
            PaymentResult result = paymentService.confirm(1L, "pay_key_1", 50000L);

            // then
            assertThat(result).isInstanceOf(PaymentResult.AlreadyProcessed.class);
            verify(tossPaymentsClient, never()).confirm(any());
        }

    }

}
