package com.fanshop.messaging;

import java.util.function.Consumer;

import com.fanshop.messaging.event.InventoryReservedEvent;

import lombok.RequiredArgsConstructor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class InventoryReservedListener {

    private final InventoryReservedHandler inventoryReservedHandler;

    @Bean
    public Consumer<InventoryReservedEvent> inventoryReservedConsumer() {
        return inventoryReservedHandler::handle;
    }

}
