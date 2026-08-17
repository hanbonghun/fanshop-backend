package com.fanshop.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;

import com.fanshop.ContextTest;
import com.fanshop.messaging.OrderEventPublisher;
import com.fanshop.messaging.event.OrderCreatedEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import tools.jackson.databind.ObjectMapper;

/**
 * 릴레이의 처리량 상한은 {@code 배치 크기 / 폴링 주기}로 정해진다. 폴링 주기는 설정으로 조절할 수 있었지만 배치 크기가 상수여서, 같은 공식의 두
 * 항 중 하나만 움직일 수 있었다. 상한이 구조적 한계인지 상수가 만든 값인지 구분하려면 둘 다 바꿔가며 재봐야 한다.
 *
 * <p>
 * 여기서는 한 틱이 설정한 개수만큼만 가져가는지를 확인한다. 값을 3으로 낮춰 두고 5건을 쌓아 두 건이 남는 것으로 본다.
 */
@TestPropertySource(properties = { "outbox.relay.initial-delay=3600000", "outbox.relay.fixed-delay=3600000",
        "outbox.relay.batch-size=3" })
class OutboxEventRelayBatchSizeTest extends ContextTest {

    private final OutboxEventRelay outboxEventRelay;

    private final OutboxEventRepository outboxEventRepository;

    private final ObjectMapper objectMapper;

    @MockitoBean
    private OrderEventPublisher orderEventPublisher;

    OutboxEventRelayBatchSizeTest(OutboxEventRelay outboxEventRelay, OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper) {
        this.outboxEventRelay = outboxEventRelay;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    @AfterEach
    void tearDown() {
        outboxEventRepository.deleteAll();
    }

    @Test
    @DisplayName("한 틱은 설정한 배치 크기만큼만 발행한다")
    void relayHonoursConfiguredBatchSize() {
        List<OutboxEvent> saved = IntStream.rangeClosed(1, 5)
            .mapToObj(i -> new OutboxEvent("ORDER_CREATED",
                    objectMapper.writeValueAsString(new OrderCreatedEvent((long) i, 2L, 3L, 1, 50000L))))
            .toList();
        outboxEventRepository.saveAll(saved);

        outboxEventRelay.relay();

        assertThat(outboxEventRepository.findAll()).filteredOn(e -> e.getStatus() == OutboxEventStatus.PUBLISHED)
            .hasSize(3);
        assertThat(outboxEventRepository.findAll()).filteredOn(e -> e.getStatus() == OutboxEventStatus.PENDING)
            .hasSize(2);
    }

}
