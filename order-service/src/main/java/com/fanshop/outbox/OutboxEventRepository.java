package com.fanshop.outbox;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * PENDING 이벤트를 배치 단위로 잠그고 가져온다. SKIP LOCKED로 다른 릴레이 인스턴스가 이미 잠근 행을 건너뛰어 중복 발행을 막는다.
     * JPQL로는 SKIP LOCKED를 표현할 수 없어 native query를 쓴다.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = 'PENDING'
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findPendingBatch(@Param("batchSize") int batchSize);

    int deleteByStatusAndPublishedAtBefore(OutboxEventStatus status, LocalDateTime threshold);

}
