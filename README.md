# fanshop-backend

**커머스의 주문 플로우 하나를 골라, 분산 환경에서 데이터 정합성이 어디서 깨지는지 끝까지 따라가 본 프로젝트.**

서비스를 나누는 것보다 나눈 뒤가 어렵다고 보고, 기능을 넓히는 대신 한 유즈케이스를 깊게 팠다.
장바구니·배송·정산은 없다 — 의도적으로.

Java 25 · Spring Boot 4 · Kafka · SAGA Choreography · 서비스 4개, DB 4개

## 정합성이 깨지는 지점과 대응

주문 플로우를 따라가며 만난 문제들. 전부 이 한 플로우 위에서 나왔다.

| 깨지는 지점 | 증상 | 대응 |
|---|---|---|
| 결제 → 재고 순서 | 재고 없는 상품에 결제가 먼저 승인됨 | Inventory-First로 순서 반전 ([#11](https://github.com/hanbonghun/fanshop-backend/pull/11)) |
| 동시 주문 | Lost Update — 100개 동시 요청에서 재현 | 비관적 락 (`SELECT … FOR UPDATE`) ([#7](https://github.com/hanbonghun/fanshop-backend/pull/7)) |
| 저장 후 발행 직전 크래시 | 주문이 PENDING에 영구 고착 | Outbox Pattern ([#22](https://github.com/hanbonghun/fanshop-backend/pull/22)) |
| at-least-once 재전송 | 같은 메시지 재수신 → 재고 이중 차감 | `processed_events` 복합 unique ([#20](https://github.com/hanbonghun/fanshop-backend/pull/20)) |
| 소비가 계속 실패 | 실패가 어디에도 안 남고 유실 | DLQ 격리 + Outbox FAILED |
| **멱등성 기록이 선커밋** | **처리 안 됐는데 처리됨으로 남아 재시도가 스킵됨** | **트랜잭션 경계 재설계** ([ADR](docs/adr/0001-outbox-expansion.md)) |
| 릴레이가 미인식 타입 수신 | 전달 안 된 행이 PUBLISHED로 기록 | 예외 전환 → FAILED 격리 |

아래 셋은 특히 오래 붙잡았던 것들이다.

### 1. 결제와 재고, 무엇이 먼저인가

처음엔 결제 → 재고 순서로 설계했다. 그런데 **재고가 없는 상품에 결제가 먼저 승인될 수 있다.** 결제를 취소하는 보상 트랜잭션이 필요해지고, 그 보상마저 실패하면 돈만 빠져나간다.

재고 확보 → 결제(Inventory-First)로 뒤집었다. 재고는 되돌리기 쉽지만 결제는 어렵다는 게 근거다. 대신 재고를 "예약" 상태로 잡아두는 단계가 생겼고, 그 예약을 언제 푸는가가 이후 모든 문제의 씨앗이 됐다.

### 2. 멱등성 가드가 이벤트를 삼켰다

모든 리스너가 멱등성 기록을 비즈니스 처리보다 **먼저** 커밋하고 있었다.

```java
processedEventRepository.save(new ProcessedEvent(eventId, PAYMENT_FAILED));  // 여기서 커밋
productService.releaseReservation(...);                                       // 여기서 실패하면?
```

일시 실패(DB 데드락 등)가 나면 기록은 이미 커밋된 상태다. 재시도가 와도 `existsBy`가 true라 **조용히 스킵된다** — 처리된 적 없는데 처리됨으로 남고, 실패가 아니니 DLQ에도 안 간다. 메시지 유실이 아니라 **처리 유실**이다.

가장 아팠던 건 product의 `handlePaymentFailed`다. 스킵되면 `releaseReservation`이 실행되지 않아 **예약 재고가 영구히 잠긴다.** 한정판이면 그 수량은 다시 팔리지 않는다.

멱등성 기록·비즈니스 처리·발행 예약을 한 트랜잭션으로 묶어 해결했다. 이 과정에서 재고 부족처럼 재시도해도 안 될 실패는 예외 대신 반환값(sealed interface)으로 바꿨다 — 예외로 던지면 트랜잭션이 rollback-only가 되어 보상 이벤트를 커밋할 수 없기 때문이다.

결정 근거와 구현 후 실제로 어땠는지는 [ADR 0001](docs/adr/0001-outbox-expansion.md)에 있다.

### 3. 실패를 격리하는 것과 SAGA를 완결시키는 것은 다르다

poison message를 DLQ로 격리하도록 바꿨다. 실패 원인이 exception 헤더와 함께 토픽에 남아 조회·재투입이 가능해졌다.

그런데 **격리된 그 메시지가 SAGA의 한 단계였다면 보상도 완결도 영영 오지 않는다.** 격리는 성공했는데 주문은 미완결로 남는다. DLQ를 넣으면서 오히려 새 구멍이 생긴 셈이다.

이건 아직 안 닫혀 있다. 아래 한계의 첫 항목이 그 이야기다.

## 한계

- **전달 보장(safety)은 다뤘지만 완결 보장(liveness)은 아직이다.** 발행 유실·중복 수신·처리 유실은 막았는데, "응답이 영영 오지 않는 것"을 알아채는 장치가 없다. payment-service가 죽거나 메시지가 DLQ로 격리되면 **아무것도 유실되지 않았는데도** 주문은 `WAITING_PAYMENT`에 남고 재고는 잠긴 채다. 예약 만료 스위퍼가 다음 작업이다
- Outbox 릴레이는 1초 주기 polling이라 SAGA 시작까지 최대 1초 지연
- 결제는 실제 PG 연동 없이 성공/실패를 시뮬레이션한다
- 서킷 브레이커 미적용 — 동기 호출(`ProductClient`) 실패 시 빠른 차단이 없다
- DLQ 재처리 자동화 없음 — 격리까지만 하고 재투입은 수동이다
- 별도 프로세스로 실행되지만 다중 노드가 아닌 **단일 개발 장비에서 검증**했다
- `outbox_events`는 `ddl-auto`로 생성되며 `local`/`local-dev`에서만 동작한다. 실제 배포에는 마이그레이션 도구가 필요하다
- 릴레이의 `FOR UPDATE SKIP LOCKED`는 MySQL 8.0.1 이상을 요구한다

## 그 외

**분산 추적 연결** ([#17](https://github.com/hanbonghun/fanshop-backend/pull/17)) — Tempo에서 Order → Payment → Order 흐름이 서비스마다 traceId가 달라 끊겼다. `enable-observation: true`로 Kafka 헤더에 trace context를 전파해 연결.

**Virtual Threads** ([#18](https://github.com/hanbonghun/fanshop-backend/pull/18)) — 처리량 13,701 → 20,378건/30초(+49%), p95 2,024ms → 991ms(-51%).
조건: `POST /orders` 단일 API, 0→500 VU 5초 램프 후 20초 유지. 변경은 설정 한 줄이며 나머지 조건 동일 — 개선분은 Tomcat 스레드 풀(200) 포화로 인한 큐 대기 제거에서 나왔고, **커넥션 풀이 다음 병목으로 남아 있다.**

**테스트의 브로커 의존 제거** — DB는 H2로 격리해뒀는데 Kafka는 안 해놔서, 테스트가 개발용 브로커 주소를 그대로 보고 있었다. 브로커가 없으면 컨텍스트 기동마다 토픽 프로비저닝 타임아웃을 소진한다 — 통과하지만 CI가 33분 걸렸다. 실제 테스트 실행 시간은 전부 1초 미만이었다. in-memory 바인더 적용 후 **33분 17초 → 19초.**

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
         Product: 재고 확인 & 예약
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
CONFIRMED    + 예약 해제
+ 재고 확정
```

서비스 4개(member / order / product / payment)가 각각 독립 프로세스이며 DB도 분리돼 있다.
오케스트레이터 없이 이벤트 연쇄로 진행하는 SAGA Choreography.

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
docker compose up -d

./gradlew :order-service:bootRun --args='--spring.profiles.active=local-dev'
./gradlew :product-service:bootRun --args='--spring.profiles.active=local-dev'
./gradlew :payment-service:bootRun --args='--spring.profiles.active=local-dev'
```

Grafana: http://localhost:3000 (admin / admin)

```bash
# 테스트 — 인프라 없이 동작한다 (DB는 H2, Kafka는 test-binder)
./gradlew test
```
