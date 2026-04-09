package com.fanshop.messaging;

import static org.mockito.Mockito.verify;

import com.fanshop.messaging.event.InventoryRejectedEvent;
import com.fanshop.messaging.event.InventoryReservedEvent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.stream.function.StreamBridge;

@ExtendWith(MockitoExtension.class)
class StockEventPublisherTest {

    @Mock
    private StreamBridge streamBridge;

    @InjectMocks
    private StockEventPublisher stockEventPublisher;

    @Test
    @DisplayName("inventory.reserved 이벤트를 output binding으로 발행한다")
    void publishInventoryReserved() {
        // given
        InventoryReservedEvent event = new InventoryReservedEvent(1L, 2L, 3L, 4, 50000L);

        // when
        stockEventPublisher.publishInventoryReserved(event);

        // then
        verify(streamBridge).send("inventoryReserved-out-0", event);
    }

    @Test
    @DisplayName("inventory.rejected 이벤트를 output binding으로 발행한다")
    void publishInventoryRejected() {
        // given
        InventoryRejectedEvent event = new InventoryRejectedEvent(1L, "재고 부족");

        // when
        stockEventPublisher.publishInventoryRejected(event);

        // then
        verify(streamBridge).send("inventoryRejected-out-0", event);
    }

}
