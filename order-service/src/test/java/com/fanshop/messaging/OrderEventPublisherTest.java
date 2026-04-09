package com.fanshop.messaging;

import static org.mockito.Mockito.verify;

import com.fanshop.messaging.event.OrderCreatedEvent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.stream.function.StreamBridge;

@ExtendWith(MockitoExtension.class)
class OrderEventPublisherTest {

    @Mock
    private StreamBridge streamBridge;

    @InjectMocks
    private OrderEventPublisher orderEventPublisher;

    @Test
    @DisplayName("order.created 이벤트를 output binding으로 발행한다")
    void publishOrderCreated() {
        // given
        OrderCreatedEvent event = new OrderCreatedEvent(1L, 2L, 3L, 4);

        // when
        orderEventPublisher.publishOrderCreated(event);

        // then
        verify(streamBridge).send("orderCreated-out-0", event);
    }

}
