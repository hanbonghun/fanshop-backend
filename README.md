# fanshop-backend

커머스의 주문 플로우 하나를 골라, 분산 환경에서 데이터 정합성이 어디서 깨지는지 따라가 본 프로젝트입니다.

서비스를 나누는 것보다 나눈 뒤가 어렵다고 봐서, 기능을 넓히는 대신 한 유즈케이스를 깊게 팠습니다.
장바구니나 배송, 정산은 없습니다. 의도적으로 뺐습니다.

Java 25 · Spring Boot 4 · Kafka · SAGA Choreography · 서비스 4개, DB 4개

## 정합성이 깨지는 지점과 대응

주문 플로우를 따라가며 만난 문제들입니다. 전부 이 한 플로우 위에서 나왔습니다.

| 깨지는 지점 | 증상 | 대응 |
|---|---|---|
| 결제 → 재고 순서 | 재고 없는 상품에 결제가 먼저 승인됨 | Inventory-First로 순서 반전 ([#11](https://github.com/hanbonghun/fanshop-backend/pull/11)) |
| 동시 주문 | Lost Update. 100개 동시 요청에서 재현 | 비관적 락 (`SELECT … FOR UPDATE`) ([#7](https://github.com/hanbonghun/fanshop-backend/pull/7)) |
| 저장 후 발행 직전 크래시 | 주문이 PENDING에 영구 고착 | Outbox Pattern ([#22](https://github.com/hanbonghun/fanshop-backend/pull/22)) |
| at-least-once 재전송 | 같은 메시지 재수신으로 재고 이중 차감 | `processed_events` 복합 unique ([#20](https://github.com/hanbonghun/fanshop-backend/pull/20)) |
| 소비가 계속 실패 | 실패가 어디에도 안 남고 유실 | DLQ 격리 + Outbox FAILED |
| 멱등성 기록이 선커밋 | 처리 안 됐는데 처리됨으로 남아 재시도가 스킵됨 | 트랜잭션 경계 재설계 ([ADR](docs/adr/0001-outbox-expansion.md)) |
| 릴레이가 미인식 타입 수신 | 전달 안 된 행이 PUBLISHED로 기록 | 예외 전환 후 FAILED 격리 |
| 결제 응답이 영영 안 옴 | 주문이 멈추고 예약 재고가 영구히 잠김 | 만료 스위퍼 |

아래 셋은 특히 오래 붙잡았던 것들입니다.

### 결제와 재고, 무엇이 먼저인가

처음엔 결제 → 재고 순서로 설계했습니다. 그런데 재고가 없는 상품에 결제가 먼저 승인될 수 있습니다. 결제를 취소하는 보상 트랜잭션이 필요해지고, 그 보상마저 실패하면 돈만 빠져나갑니다.

재고 확보 → 결제(Inventory-First)로 뒤집었습니다. 재고는 되돌리기 쉽지만 결제는 어렵다는 게 근거입니다. 대신 재고를 예약 상태로 잡아두는 단계가 생겼고, 그 예약을 언제 푸는가가 이후 모든 문제의 씨앗이 됐습니다.

### 멱등성 가드가 이벤트를 삼켰다

모든 리스너가 멱등성 기록을 비즈니스 처리보다 먼저 커밋하고 있었습니다.

```java
processedEventRepository.save(new ProcessedEvent(eventId, PAYMENT_FAILED));  // 여기서 커밋
productService.releaseReservation(...);                                       // 여기서 실패하면?
```

일시 실패(DB 데드락 등)가 나면 기록은 이미 커밋된 상태입니다. 재시도가 와도 `existsBy`가 true라 조용히 스킵됩니다. 처리된 적 없는데 처리됨으로 남고, 실패가 아니니 DLQ에도 안 갑니다. 메시지 유실이 아니라 처리 유실입니다.

가장 아팠던 건 product의 `handlePaymentFailed`입니다. 스킵되면 `releaseReservation`이 실행되지 않아 예약 재고가 영구히 잠깁니다. 한정판이면 그 수량은 다시 팔리지 않습니다.

멱등성 기록·비즈니스 처리·발행 예약을 한 트랜잭션으로 묶어 해결했습니다. 이 과정에서 재고 부족처럼 재시도해도 안 될 실패는 예외 대신 반환값(sealed interface)으로 바꿨습니다. 예외로 던지면 트랜잭션이 rollback-only가 되어 보상 이벤트를 커밋할 수 없기 때문입니다.

결정 근거와 구현 후 실제로 어땠는지는 [ADR 0001](docs/adr/0001-outbox-expansion.md)에 있습니다.

### 실패를 격리하는 것과 SAGA를 완결시키는 것은 다르다

poison message를 DLQ로 격리하도록 바꿨습니다. 실패 원인이 exception 헤더와 함께 토픽에 남아 조회와 재투입이 가능해졌습니다.

그런데 격리된 그 메시지가 SAGA의 한 단계였다면 보상도 완결도 영영 오지 않습니다. 격리는 성공했는데 주문은 미완결로 남습니다. Outbox도 멱등성도 DLQ도 전부 온 메시지를 다루는 장치라, 오지 않는 메시지를 알아채는 주체가 없었습니다.

만료 스위퍼를 붙였습니다. 결제 대기가 임계 시간을 넘긴 주문을 EXPIRED로 바꾸고 예약 해제 이벤트를 Outbox에 기록합니다. 상태 전이와 기록은 한 트랜잭션입니다. 만료만 되고 이벤트를 못 남기면 재고가 영원히 잠기기 때문입니다.

여기서 새 문제가 하나 나옵니다. 만료 처리한 뒤에 결제 성공이 도착하면 어떻게 할 것인가. 돈은 이미 나갔고 재고는 이미 풀려서 다른 주문에 팔렸을 수 있습니다. 주문을 되살리면 재고가 음수가 되거나 또 다른 보상이 필요해집니다.

`CONFIRMED`가 아니라 `REFUND_REQUIRED`로 보냅니다. 재고는 되잡지 않고, 돈이 나갔다는 사실만 상태로 남겨 운영 개입 대상으로 둡니다. 자동으로 해결하지 않는 쪽을 택했습니다.

이 작업을 하면서 `Order`에 상태 전이 가드가 전혀 없다는 것도 드러났습니다. `CANCELLED` 주문이 늦게 온 결제로 조용히 `CONFIRMED`가 될 수 있었습니다.

## 한계

- 만료 임계값(기본 5분)은 근거 있는 측정값이 아니라 가정입니다. 결제가 실제로 그보다 오래 걸리는 경우를 확인하지 않았습니다
- `PENDING` 상태로 고착된 주문은 만료 대상이 아닙니다. 재고 예약 여부를 order-service가 알 수 없어 안전한 해제가 불가능합니다. 재고를 잠그지 않으므로 피해는 작습니다
- `REFUND_REQUIRED` 주문을 처리하는 운영 도구가 없습니다. 상태로 남길 뿐입니다
- Outbox 릴레이는 1초 주기 polling이라 SAGA 시작까지 최대 1초 지연이 생깁니다
- 결제는 실제 PG 연동 없이 성공/실패를 시뮬레이션합니다
- 서킷 브레이커 미적용. 동기 호출(`ProductClient`) 실패 시 빠른 차단이 없습니다
- DLQ 재처리 자동화가 없습니다. 격리까지만 하고 재투입은 수동입니다
- 별도 프로세스로 실행되지만 다중 노드가 아닌 단일 개발 장비에서 검증했습니다
- `outbox_events`는 `ddl-auto`로 생성되며 `local`/`local-dev`에서만 동작합니다. 실제 배포에는 마이그레이션 도구가 필요합니다
- 릴레이의 `FOR UPDATE SKIP LOCKED`는 MySQL 8.0.1 이상을 요구합니다

## 그 외

**분산 추적 연결** ([#17](https://github.com/hanbonghun/fanshop-backend/pull/17))
Tempo에서 Order → Payment → Order 흐름이 서비스마다 traceId가 달라 끊겼습니다. `enable-observation: true`로 Kafka 헤더에 trace context를 전파해 연결했습니다.

**Virtual Threads** ([#18](https://github.com/hanbonghun/fanshop-backend/pull/18))
처리량 13,701 → 20,378건/30초(+49%), p95 2,024ms → 991ms(-51%).
조건은 `POST /orders` 단일 API, 0→500 VU 5초 램프 후 20초 유지입니다. 변경은 설정 한 줄이고 나머지 조건은 동일합니다. 개선분은 Tomcat 스레드 풀(200) 포화로 인한 큐 대기 제거에서 나왔고, 커넥션 풀이 다음 병목으로 남아 있습니다.

**트랜잭션 안의 동기 호출 분리**
위 항목이 지목한 커넥션 풀 병목을 따라가 봤습니다. `createOrder`가 상품 조회(동기 HTTP)를 트랜잭션 안에서 하고 있어서, 네트워크 왕복이 끝날 때까지 DB 커넥션을 붙들고 있었습니다. 커넥션 풀 크기가 곧 동시 처리량의 상한이 되는 구조입니다.

쓰기 구간만 `TransactionTemplate`으로 묶어 조회를 밖으로 뺐습니다. 메서드를 쪼개서 `@Transactional`을 붙이는 방식은 같은 빈 안의 자기 호출이라 프록시를 거치지 않아 애너테이션이 조용히 무시됩니다.

같은 spike 조건으로 변경 전 1회, 변경 후 2회 측정했습니다.

| | 변경 전 | 변경 후 |
|---|---|---|
| 처리량 | 255 rps | 311 / 327 rps |
| 평균 | 2,304 ms | 1,949 / 1,785 ms |
| p95 | 3,976 ms | 4,553 / 4,739 ms |

처리량과 평균은 20% 안팎 좋아졌지만 p95는 15~19% 나빠졌습니다. 커넥션을 일찍 놓으니 더 많은 요청이 동시에 진입하고, 그만큼 뒤쪽 병목에서 꼬리가 길어진 것으로 보입니다. 한정판처럼 최악 응답이 중요한 시나리오에서는 이득이라고 단정할 수 없습니다. 커넥션 풀 크기를 함께 조정해야 판단이 서는데, 거기까지는 하지 않았습니다.

**테스트의 브로커 의존 제거**
DB는 H2로 격리해뒀는데 Kafka는 안 해놔서, 테스트가 개발용 브로커 주소를 그대로 보고 있었습니다. 브로커가 없으면 컨텍스트 기동마다 토픽 프로비저닝 타임아웃을 소진합니다. 통과는 하지만 CI가 33분 걸렸고, 실제 테스트 실행 시간은 전부 1초 미만이었습니다. in-memory 바인더 적용 후 33분 17초에서 19초가 됐습니다.

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

응답이 오지 않으면 ─▶ 스위퍼가 EXPIRED + order.expired ─▶ Product: 예약 해제
```

서비스 4개(member / order / product / payment)가 각각 독립 프로세스이며 DB도 분리돼 있습니다.
오케스트레이터 없이 이벤트 연쇄로 진행하는 SAGA Choreography입니다.

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
# 테스트는 인프라 없이 동작합니다 (DB는 H2, Kafka는 test-binder)
./gradlew test
```
