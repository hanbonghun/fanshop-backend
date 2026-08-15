package com.fanshop.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fanshop.ContextTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@TestPropertySource(properties = { "outbox.relay.initial-delay=3600000", "outbox.relay.fixed-delay=3600000" })
class OutboxEventRepositoryTest extends ContextTest {

    private final OutboxEventRepository outboxEventRepository;

    private final TransactionTemplate transactionTemplate;

    OutboxEventRepositoryTest(OutboxEventRepository outboxEventRepository, TransactionTemplate transactionTemplate) {
        this.outboxEventRepository = outboxEventRepository;
        this.transactionTemplate = transactionTemplate;
    }

    @AfterEach
    void tearDown() {
        outboxEventRepository.deleteAll();
    }

    @Test
    @DisplayName("배치 크기만큼만 조회한다")
    void limitsBatchSize() {
        for (int i = 0; i < 5; i++) {
            outboxEventRepository.save(new OutboxEvent("ORDER_CREATED", "{}"));
        }

        assertThat(outboxEventRepository.findPendingBatch(3)).hasSize(3);
    }

    @Test
    @DisplayName("다른 트랜잭션이 잠근 행은 건너뛴다 — 중복 발행 방지")
    void skipsLockedRows() throws Exception {
        outboxEventRepository.save(new OutboxEvent("ORDER_CREATED", "{}"));

        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        AtomicInteger secondFetched = new AtomicInteger(-1);

        Thread first = new Thread(() -> transactionTemplate.executeWithoutResult(status -> {
            outboxEventRepository.findPendingBatch(100);
            firstLocked.countDown();
            await(secondDone);
        }));

        Thread second = new Thread(() -> {
            await(firstLocked);
            transactionTemplate
                .executeWithoutResult(status -> secondFetched.set(outboxEventRepository.findPendingBatch(100).size()));
            secondDone.countDown();
        });

        first.start();
        second.start();
        first.join(10_000);
        second.join(10_000);

        assertThat(secondFetched.get()).isZero();
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
