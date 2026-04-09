package com.fanshop.messaging;

import com.fanshop.messaging.event.InventoryRejectedEvent;
import com.fanshop.messaging.event.InventoryReservedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StockEventPublisher {

    private static final String INVENTORY_RESERVED_BINDING = "inventoryReserved-out-0";
    private static final String INVENTORY_REJECTED_BINDING = "inventoryRejected-out-0";

    private final StreamBridge streamBridge;

    public void publishInventoryReserved(InventoryReservedEvent event) {
        streamBridge.send(INVENTORY_RESERVED_BINDING, event);
        log.info("Published inventory.reserved — orderId={}", event.orderId());
    }

    public void publishInventoryRejected(InventoryRejectedEvent event) {
        streamBridge.send(INVENTORY_REJECTED_BINDING, event);
        log.info("Published inventory.rejected — orderId={}, reason={}", event.orderId(), event.reason());
    }

}
