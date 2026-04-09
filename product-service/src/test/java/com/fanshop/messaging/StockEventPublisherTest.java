package com.fanshop.messaging;

import static org.mockito.Mockito.verify;

import com.fanshop.messaging.event.StockResultEvent;

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
    @DisplayName("stock.result 이벤트를 output binding으로 발행한다")
    void publishStockResult() {
        // given
        StockResultEvent event = new StockResultEvent(1L, true, null);

        // when
        stockEventPublisher.publishStockResult(event);

        // then
        verify(streamBridge).send("stockResult-out-0", event);
    }

}
