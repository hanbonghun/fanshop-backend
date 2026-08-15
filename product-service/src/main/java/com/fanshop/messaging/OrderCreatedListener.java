package com.fanshop.messaging;

import java.util.function.Consumer;

import com.fanshop.messaging.event.OrderCreatedEvent;

import lombok.RequiredArgsConstructor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class OrderCreatedListener {

    private final OrderCreatedHandler orderCreatedHandler;

    @Bean
    public Consumer<OrderCreatedEvent> orderCreatedConsumer() {
        return orderCreatedHandler::handle;
    }

}
