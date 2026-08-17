package com.fanshop.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fanshop.messaging.event.InventoryReservedEvent;
import com.fanshop.outbox.OutboxRecorder;
import com.fanshop.payment.service.PaymentService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InventoryReservedHandlerTest {

    @Mock
    private PaymentService paymentService;

    @Mock
    private OutboxRecorder outboxRecorder;

    @InjectMocks
    private InventoryReservedHandler inventoryReservedHandler;

    private final InventoryReservedEvent event = new InventoryReservedEvent(1L, 2L, 3L, 1, 50000L);

    @Nested
    @DisplayName("handle")
    class Handle {

        @Test
        @DisplayName("결제를 실행하지 않고 결제 대기만 만든다 — 승인은 구매자 인증 뒤에야 가능하다")
        void prepareOnly() {
            // when
            inventoryReservedHandler.handle(event);

            // then
            verify(paymentService).prepare(event);
        }

        @Test
        @DisplayName("재고 예약만으로는 Outbox에 아무것도 기록하지 않는다 — 아직 결제된 것이 없다")
        void recordsNothing() {
            // when
            inventoryReservedHandler.handle(event);

            // then
            verify(outboxRecorder, never()).record(any(), any());
        }

    }

}
