package com.fanshop.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import com.fanshop.ContextTest;
import com.fanshop.messaging.PaymentEventPublisher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@TestPropertySource(properties = { "outbox.relay.initial-delay=3600000", "outbox.relay.fixed-delay=3600000" })
class OutboxEventPurgeTest extends ContextTest {

    private final OutboxEventRelay outboxEventRelay;

    private final OutboxEventRepository outboxEventRepository;

    @MockitoBean
    private PaymentEventPublisher paymentEventPublisher;

    OutboxEventPurgeTest(OutboxEventRelay outboxEventRelay, OutboxEventRepository outboxEventRepository) {
        this.outboxEventRelay = outboxEventRelay;
        this.outboxEventRepository = outboxEventRepository;
    }

    @AfterEach
    void tearDown() {
        outboxEventRepository.deleteAll();
    }

    @Test
    @DisplayName("보관 기간이 지나지 않은 PUBLISHED는 삭제하지 않는다")
    void respectsAgeThreshold() {
        OutboxEvent published = new OutboxEvent("PAYMENT_COMPLETED", "{}");
        published.markPublished();
        outboxEventRepository.save(published);

        OutboxEvent failed = new OutboxEvent("PAYMENT_COMPLETED", "{}");
        for (int i = 0; i < OutboxEventRelay.MAX_ATTEMPTS; i++) {
            failed.recordFailure(OutboxEventRelay.MAX_ATTEMPTS);
        }
        outboxEventRepository.save(failed);

        outboxEventRepository.save(new OutboxEvent("PAYMENT_COMPLETED", "{}"));

        // Threshold is 1 day in the past, all rows are fresh (publishedAt=now or null)
        int deleted = outboxEventRelay.purgeBefore(LocalDateTime.now().minusDays(1));

        assertThat(deleted).isEqualTo(0);
        assertThat(outboxEventRepository.findAll()).extracting(OutboxEvent::getStatus)
            .containsExactlyInAnyOrder(OutboxEventStatus.PUBLISHED, OutboxEventStatus.FAILED,
                    OutboxEventStatus.PENDING);
    }

    @Test
    @DisplayName("보관 기간이 지난 PUBLISHED만 삭제하고 FAILED와 PENDING은 남긴다")
    void purgesOnlyPublished() {
        OutboxEvent published = new OutboxEvent("PAYMENT_COMPLETED", "{}");
        published.markPublished();
        outboxEventRepository.save(published);

        OutboxEvent failed = new OutboxEvent("PAYMENT_COMPLETED", "{}");
        for (int i = 0; i < OutboxEventRelay.MAX_ATTEMPTS; i++) {
            failed.recordFailure(OutboxEventRelay.MAX_ATTEMPTS);
        }
        outboxEventRepository.save(failed);

        outboxEventRepository.save(new OutboxEvent("PAYMENT_COMPLETED", "{}"));

        // Threshold is in future, all PUBLISHED rows qualify for deletion
        int deleted = outboxEventRelay.purgeBefore(LocalDateTime.now().plusSeconds(1));

        assertThat(deleted).isEqualTo(1);
        assertThat(outboxEventRepository.findAll()).extracting(OutboxEvent::getStatus)
            .containsExactlyInAnyOrder(OutboxEventStatus.FAILED, OutboxEventStatus.PENDING);
    }

}
