package com.fanshop.outbox;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * 이벤트를 직렬화해 Outbox에 저장한다. 호출자의 트랜잭션에 참여하므로 비즈니스 처리와 함께 커밋되거나 함께 롤백된다.
 */
@Component
@RequiredArgsConstructor
public class OutboxRecorder {

    private final OutboxEventRepository outboxEventRepository;

    private final ObjectMapper objectMapper;

    public void record(String eventType, Object payload) {
        outboxEventRepository.save(new OutboxEvent(eventType, serialize(payload)));
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        }
        catch (Exception e) {
            throw new IllegalStateException("Outbox 이벤트 직렬화 실패", e);
        }
    }

}
