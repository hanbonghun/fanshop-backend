package com.fanshop.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fanshop.ContextTest;
import com.fanshop.messaging.event.InventoryReservedEvent;
import com.fanshop.outbox.OutboxEventRepository;
import com.fanshop.payment.domain.PaymentRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = { "outbox.relay.initial-delay=3600000", "outbox.relay.fixed-delay=3600000" })
class InventoryReservedHandlerAtomicityTest extends ContextTest {

    private final InventoryReservedHandler handler;

    private final PaymentRepository paymentRepository;

    private final OutboxEventRepository outboxEventRepository;

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
        handler.handle(new InventoryReservedEvent(1L, 2L, 3L, 4, 50000L));

        assertThat(paymentRepository.existsByOrderId(1L)).isTrue();
        assertThat(outboxEventRepository.findAll()).singleElement()
            .satisfies(e -> assertThat(e.getEventType()).isEqualTo("PAYMENT_COMPLETED"));
    }

    @Test
    @DisplayName("이미 처리된 결제는 중복 저장하지 않는다")
    void skipsAlreadyProcessed() {
        InventoryReservedEvent event = new InventoryReservedEvent(1L, 2L, 3L, 4, 50000L);
        handler.handle(event);
        outboxEventRepository.deleteAll();

        handler.handle(event);

        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isZero();
    }

}
