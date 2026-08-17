package com.fanshop.messaging;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fanshop.common.idempotency.ProcessedEvent;
import com.fanshop.common.idempotency.ProcessedEventRepository;
import com.fanshop.messaging.event.OrderExpiredEvent;
import com.fanshop.product.service.ProductService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class OrderExpiredHandlerTest {

    @Mock
    private ProductService productService;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @InjectMocks
    private OrderExpiredHandler orderExpiredHandler;

    private final OrderExpiredEvent event = new OrderExpiredEvent(1L, 3L, 4);

    @Test
    @DisplayName("주문 만료를 받으면 예약 재고를 해제한다")
    void releasesReservation() {
        given(processedEventRepository.existsByEventIdAndEventType("1", "ORDER_EXPIRED")).willReturn(false);

        orderExpiredHandler.handleOrderExpired(event);

        verify(processedEventRepository).save(any(ProcessedEvent.class));
        verify(productService).releaseReservation(1L, 3L, 4);
    }

    @Test
    @DisplayName("이미 처리된 만료 이벤트면 재고를 다시 해제하지 않는다")
    void skipsDuplicate() {
        given(processedEventRepository.existsByEventIdAndEventType("1", "ORDER_EXPIRED")).willReturn(true);

        orderExpiredHandler.handleOrderExpired(event);

        verify(productService, never()).releaseReservation(anyLong(), anyLong(), anyInt());
    }

}
