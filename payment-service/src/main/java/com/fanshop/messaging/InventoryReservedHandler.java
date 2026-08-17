package com.fanshop.messaging;

import com.fanshop.messaging.event.InventoryReservedEvent;
import com.fanshop.payment.service.PaymentService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고가 예약되면 결제를 실행하는 것이 아니라 결제 대기를 만든다.
 * <p>
 * 실제 PG 결제는 구매자가 결제창에서 카드사 인증을 마쳐야 승인할 수 있다. 서버가 이벤트만 보고 단독으로 시작할 수 있는 절차가 아니다. 그래서 이
 * 리스너는 금액과 주문 정보를 확정해 저장하는 데까지만 관여하고, 승인은 구매자가 인증을 마친 뒤 confirm API로 들어온다.
 * <p>
 * 결제가 일어나지 않았으므로 여기서는 Outbox에 기록할 것도 없다. 결제 승인과 발행 예약을 한 트랜잭션으로 묶는 책임은 confirm 경로로 옮겼다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryReservedHandler {

    private final PaymentService paymentService;

    @Transactional
    public void handle(InventoryReservedEvent event) {
        log.info("Received inventory.reserved — orderId={}", event.orderId());
        paymentService.prepare(event);
    }

}
