package com.fanshop.messaging;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.BDDMockito.given;
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
        OrderCreatedEvent event = new OrderCreatedEvent(1L, 2L, 3L, 4, 50000L);
        given(streamBridge.send("orderCreated-out-0", event)).willReturn(true);

        // when
        orderEventPublisher.publishOrderCreated(event);

        // then
        verify(streamBridge).send("orderCreated-out-0", event);
    }

    @Test
    @DisplayName("send가 false를 반환하면 예외를 던져 발행 실패를 알린다")
    void publishOrderCreatedFails() {
        // given
        OrderCreatedEvent event = new OrderCreatedEvent(1L, 2L, 3L, 4, 50000L);
        given(streamBridge.send("orderCreated-out-0", event)).willReturn(false);

        // when & then
        assertThatIllegalStateException().isThrownBy(() -> orderEventPublisher.publishOrderCreated(event))
            .withMessageContaining("orderId=1");
    }

}
