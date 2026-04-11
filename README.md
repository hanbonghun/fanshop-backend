# fanshop-backend

## 목표

분산 환경을 가정하고, 하나의 주문 플로우가 여러 서비스에 걸쳐 어떻게 완성되는지 직접 구현해보는 것.
특히 **이벤트 기반 비동기 처리**에서 데이터 정합성을 어떻게 맞추는지를 중점적으로 다뤘다.

## 가정한 환경

한정판 상품처럼 **트래픽이 특정 시점에 집중되는 커머스**.
동기 API 호출은 서비스 간 강결합과 재고 초과 판매 문제를 만들기 쉽다는 전제에서 Kafka 기반 비동기 이벤트로 접근했다.

## 주문 플로우

```
[POST /orders]
     │
     ▼
 Order 생성 (PENDING) + outbox_events 저장 (같은 트랜잭션)
     │
     ▼
 OutboxEventRelay ──▶ Kafka: order.created
                             │
                 ┌───────────┘
                 ▼
         Product: 재고 확인 & 차감
                 │
        ┌────────┴────────┐
        ▼                 ▼
  inventory.reserved  inventory.rejected
        │                 │
        ▼                 ▼
  Payment: 결제 처리   Order: CANCELLED
        │
  ┌─────┴──────┐
  ▼            ▼
payment      payment
.completed   .failed
  │            │
  ▼            ▼
Order:       Order: CANCELLED
CONFIRMED    + 재고 복원
```

## 주요 이슈와 결정

**재고 순서 ([#11](https://github.com/hanbonghun/fanshop-backend/pull/11))**
처음엔 결제 → 재고 순서로 설계했는데, 재고가 없는 상품에 결제가 먼저 승인될 수 있다는 문제를 발견.
재고 확보 → 결제 순서(Inventory-First)로 변경했다.

**재고 동시성 ([#7](https://github.com/hanbonghun/fanshop-backend/pull/7))**
100개 동시 요청 테스트에서 락 없이는 Lost Update 발생 확인.
비관적 락(`SELECT ... FOR UPDATE`)으로 해결. 트래픽 집중 환경에서 낙관적 락은 재시도 폭풍 우려가 있어 비관적 락을 먼저 적용하고, 실제 병목 확인 후 Redis 선점 방식 전환을 2단계로 남겨뒀다.

**이벤트 발행 신뢰성 ([#22](https://github.com/hanbonghun/fanshop-backend/pull/22))**
Order 저장 후 Kafka 발행 직전에 크래시나면 주문이 PENDING에서 영원히 멈추는 문제.
Outbox Pattern으로 해결 — `outbox_events` 테이블에 Order와 같은 트랜잭션으로 저장하고, 별도 릴레이가 1초마다 발행한다.

**수신 멱등성 ([#20](https://github.com/hanbonghun/fanshop-backend/pull/20))**
Kafka at-least-once delivery로 같은 메시지가 두 번 올 수 있다.
`processed_events(event_id, event_type)` 복합 unique 제약으로 중복 수신 차단.

**분산 추적 연결 ([#17](https://github.com/hanbonghun/fanshop-backend/pull/17))**
Grafana Tempo에서 Order → Payment → Order 흐름이 서비스마다 traceId가 달라 끊기는 문제.
`spring.cloud.stream.kafka.binder.enable-observation: true` 설정으로 Kafka 헤더에 trace context 자동 전파.

**Virtual Threads ([#18](https://github.com/hanbonghun/fanshop-backend/pull/18))**
500 VU 스파이크 테스트에서 Virtual Threads 적용 후 처리량 +49%, p95 응답시간 -51% 확인.

## 한계

- Outbox relay는 1초 주기로 polling하기 때문에 SAGA 시작까지 최대 1초 지연이 생긴다
- 결제 서비스는 실제 PG 연동 없이 성공/실패를 시뮬레이션한다
- 서킷 브레이커 미적용 — 동기 호출(`ProductClient`) 실패 시 빠른 차단이 없다
- 실제 배포는 단일 프로세스다. 분산 환경을 **가정한** 구조다

## 기술 스택

| 영역 | 기술 |
|------|------|
| Language / Runtime | Java 25, Virtual Threads |
| Framework | Spring Boot 4.0, Spring Cloud Stream |
| Messaging | Apache Kafka |
| Persistence | Spring Data JPA, MySQL |
| Observability | Prometheus, Grafana, Loki, Tempo, OpenTelemetry |
| Infra | Docker Compose |
| Load Test | k6 |

## 로컬 실행

```bash
# 인프라 시작
docker compose up -d

# 각 서비스 실행
./gradlew :order-service:bootRun --args='--spring.profiles.active=local-dev'
./gradlew :product-service:bootRun --args='--spring.profiles.active=local-dev'
./gradlew :payment-service:bootRun --args='--spring.profiles.active=local-dev'
```

Grafana: http://localhost:3000 (admin / admin)

```bash
# 테스트
./gradlew test
```
