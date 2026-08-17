package com.fanshop.payment.api;

import com.fanshop.payment.service.PaymentConfirmService;
import com.fanshop.support.response.ApiResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentConfirmService paymentConfirmService;

    /**
     * 구매자 인증이 끝난 뒤에만 호출될 수 있는 승인 요청. 재고 예약 시점에 저장해 둔 금액과 대조한 뒤 PG에 승인을 넘긴다.
     */
    @PostMapping("/confirm")
    public ApiResponse<?> confirm(@RequestBody ConfirmPaymentRequest request) {
        paymentConfirmService.confirm(request.orderId(), request.paymentKey(), request.amount());
        return ApiResponse.success();
    }

}
