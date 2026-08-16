package com.fanshop.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fanshop.ContextTest;
import com.fanshop.messaging.OrderEventPublisher;
import com.fanshop.messaging.event.OrderCreatedEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import tools.jackson.databind.ObjectMapper;

/**
 * {@link OutboxEventRelayTest}는 순수 Mockito라 markPublished()의 dirty checking이 실제로 커밋되는지,
 * {@code @EnableScheduling}이 빠지지 않았는지를 검증하지 못한다. 컨텍스트를 띄워 저장 결과를 리포지토리로 다시 읽어 확인한다.
 */
@TestPropertySource(properties = { "outbox.relay.initial-delay=3600000", "outbox.relay.fixed-delay=3600000" })
class OutboxEventRelayContextTest extends ContextTest {

    private final OutboxEventRelay outboxEventRelay;

    private final OutboxEventRepository outboxEventRepository;

    private final ObjectMapper objectMapper;

    private final ScheduledAnnotationBeanPostProcessor scheduledAnnotationBeanPostProcessor;

    @MockitoBean
    private OrderEventPublisher orderEventPublisher;

    OutboxEventRelayContextTest(OutboxEventRelay outboxEventRelay, OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper, ScheduledAnnotationBeanPostProcessor scheduledAnnotationBeanPostProcessor) {
        this.outboxEventRelay = outboxEventRelay;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.scheduledAnnotationBeanPostProcessor = scheduledAnnotationBeanPostProcessor;
    }

    @AfterEach
    void tearDown() {
        outboxEventRepository.deleteAll();
    }

    @Test
    @DisplayName("relay()는 PENDING 행을 실제로 DB에서 PUBLISHED로 커밋한다")
    void publishesAndPersistsToDatabase() {
        OrderCreatedEvent event = new OrderCreatedEvent(1L, 2L, 3L, 1, 50000L);
        OutboxEvent saved = outboxEventRepository
            .save(new OutboxEvent("ORDER_CREATED", objectMapper.writeValueAsString(event)));

        outboxEventRelay.relay();

        OutboxEvent reloaded = outboxEventRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
    }

    @Test
    @DisplayName("@EnableScheduling이 실제로 등록되어 있다 — 빠지면 릴레이가 아예 실행되지 않는다")
    void schedulingIsWired() {
        assertThat(scheduledAnnotationBeanPostProcessor.getScheduledTasks()).isNotEmpty();
    }

}
