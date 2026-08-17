package com.fanshop.payment.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fanshop.ContextTest;
import com.fanshop.payment.service.PaymentConfirmService;
import com.fanshop.support.error.CoreException;
import com.fanshop.support.error.ErrorType;

import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class PaymentControllerTest extends ContextTest {

    private final MockMvc mockMvc;

    private final ObjectMapper objectMapper;

    @MockitoBean
    private PaymentConfirmService paymentConfirmService;

    PaymentControllerTest(MockMvc mockMvc, ObjectMapper objectMapper) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    private String body(Object request) {
        return objectMapper.writeValueAsString(request);
    }

    @Nested
    @DisplayName("POST /api/v1/payments/confirm")
    class Confirm {

        @Test
        @DisplayName("구매자 인증으로 받은 paymentKey와 금액을 그대로 승인 요청에 전달한다")
        void delegatesToConfirmService() throws Exception {
            mockMvc
                .perform(post("/api/v1/payments/confirm").contentType(MediaType.APPLICATION_JSON)
                    .content(body(new ConfirmPaymentRequest(1L, "pay_key_1", 50000L))))
                .andExpect(status().isOk());

            verify(paymentConfirmService).confirm(1L, "pay_key_1", 50000L);
        }

        @Test
        @DisplayName("금액이 조작되면 400과 PAYMENT_AMOUNT_MISMATCH를 반환한다")
        void returnsBadRequestOnAmountMismatch() throws Exception {
            willThrow(new CoreException(ErrorType.PAYMENT_AMOUNT_MISMATCH)).given(paymentConfirmService)
                .confirm(any(), any(), org.mockito.ArgumentMatchers.anyLong());

            mockMvc
                .perform(post("/api/v1/payments/confirm").contentType(MediaType.APPLICATION_JSON)
                    .content(body(new ConfirmPaymentRequest(1L, "pay_key_1", 1000L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_AMOUNT_MISMATCH"));
        }

    }

}
