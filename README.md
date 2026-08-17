# fanshop-backend

커머스의 주문 플로우 하나를 골라, 분산 환경에서 데이터 정합성이 어디서 깨지는지 따라가 본 프로젝트입니다.

서비스를 나누는 것보다 나눈 뒤가 어렵다고 봐서, 기능을 넓히는 대신 한 유즈케이스를 깊게 팠습니다.
장바구니나 배송, 정산은 없습니다. 의도적으로 뺐습니다.

정합성을 지키는 구조를 만들었으면 그 대가도 알아야 한다고 봐서, 처리량 상한도 함께 쟀습니다. 측정 결과 병목은 재고 행 하나였고, 병목이 아니라고 확인된 것은 튜닝하지 않았습니다. 전체 기록은 [docs/measurements](docs/measurements)에 있습니다.

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
| 재고 예약이 결제를 자동 실행 | 구매자 인증 없이 결제가 성립할 수 없는데 서버가 단독 진행 | 승인을 confirm API로 분리 ([#26](https://github.com/hanbonghun/fanshop-backend/pull/26)) |
| 승인 금액을 클라이언트가 지정 | 금액을 조작해 승인 요청 가능 | 예약 시점에 확정 저장한 금액과 대조 ([#26](https://github.com/hanbonghun/fanshop-backend/pull/26)) |
| 주문 요청이 두 번 도달 | 더블클릭·재시도에 주문과 재고 예약이 두 건 | `Idempotency-Key` + `UNIQUE(member_id, key)` ([#26](https://github.com/hanbonghun/fanshop-backend/pull/26)) |
| 만료 해제 뒤 늦은 결제 성공 | product가 주문 상태를 몰라 확정 → 예약량이 음수, 팔지 않은 재고 증발 | 주문별 `InventoryReservation` 상태 전이 ([#28](https://github.com/hanbonghun/fanshop-backend/pull/28)) |
| 확정 뒤 늦은 만료 이벤트 | 팔린 재고가 되살아남 | 같은 전이 가드 ([#28](https://github.com/hanbonghun/fanshop-backend/pull/28)) |
| 음수 수량 요청 | 예약량이 줄고 확정 시 재고가 늘어남 | Bean Validation + 도메인 수량 가드 ([#28](https://github.com/hanbonghun/fanshop-backend/pull/28)) |
| 보상 경로를 실제로 재현할 수단 없음 | Mock PG가 항상 승인해 `payment.failed` 경로를 종단으로 못 봄 | `paymentKey` 접두사 실패 트리거 ([#29](https://github.com/hanbonghun/fanshop-backend/pull/29)) |

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

### 집계 수량만으로는 이벤트 순서를 판별할 수 없다

`REFUND_REQUIRED`는 order-service의 결정입니다. product-service에는 그 정보가 없어서, 만료로 예약을 해제한 뒤 결제 성공이 도착하면 주문이 이미 끝난 줄 모르고 확정합니다. `reservedQuantity`가 음수가 되고 팔지 않은 재고가 사라집니다. 반대 순서에서는 늦게 온 만료가 확정된 예약을 다시 해제해 팔린 재고를 되살립니다.

`processed_events` 멱등성도 이걸 막지 못합니다. 이벤트 타입이 서로 달라 둘 다 "처음 보는 이벤트"이기 때문입니다.

상품의 집계 수량만 보면 두 경우의 조건이 같아 보이는 것이 원인이었습니다. 주문별 `InventoryReservation(RESERVED/CONFIRMED/RELEASED)`을 두고 전이가 가능할 때만 수량을 움직이도록 바꿨습니다. 이벤트가 어떤 순서로 오든 결과가 같아집니다.

도메인에도 가드를 남겼습니다. 전이 판정을 통과하더라도 예약량보다 많은 확정·해제는 거절합니다. 순서 판별은 예약이 하고, 이 가드는 수량이 음수로 내려가지 않게 하는 마지막 방어선입니다.

### 실패를 격리하는 것과 SAGA를 완결시키는 것은 다르다

poison message를 DLQ로 격리하도록 바꿨습니다. 실패 원인이 exception 헤더와 함께 토픽에 남아 조회와 재투입이 가능해졌습니다.

그런데 격리된 그 메시지가 SAGA의 한 단계였다면 보상도 완결도 영영 오지 않습니다. 격리는 성공했는데 주문은 미완결로 남습니다. Outbox도 멱등성도 DLQ도 전부 온 메시지를 다루는 장치라, 오지 않는 메시지를 알아채는 주체가 없었습니다.

만료 스위퍼를 붙였습니다. 결제 대기가 임계 시간을 넘긴 주문을 EXPIRED로 바꾸고 예약 해제 이벤트를 Outbox에 기록합니다. 상태 전이와 기록은 한 트랜잭션입니다. 만료만 되고 이벤트를 못 남기면 재고가 영원히 잠기기 때문입니다.

여기서 새 문제가 하나 나옵니다. 만료 처리한 뒤에 결제 성공이 도착하면 어떻게 할 것인가. 돈은 이미 나갔고 재고는 이미 풀려서 다른 주문에 팔렸을 수 있습니다. 주문을 되살리면 재고가 음수가 되거나 또 다른 보상이 필요해집니다.

`CONFIRMED`가 아니라 `REFUND_REQUIRED`로 보냅니다. 재고는 되잡지 않고, 돈이 나갔다는 사실만 상태로 남겨 운영 개입 대상으로 둡니다. 자동으로 해결하지 않는 쪽을 택했습니다.

이 작업을 하면서 `Order`에 상태 전이 가드가 전혀 없다는 것도 드러났습니다. `CANCELLED` 주문이 늦게 온 결제로 조용히 `CONFIRMED`가 될 수 있었습니다.

## 처리량 상한은 어디인가

정합성을 지키는 구조를 만들었으면 그 대가도 알아야 한다고 봐서, 재고가 소진되지 않는 조건에서 상한을 쟀습니다. 전체 결과와 재현 방법은 [측정 0001](docs/measurements/0001-inventory-contention.md)에 있습니다.

| 단계 | 소화율 | 제약 |
|---|---:|---|
| `POST /orders` 수용 | 647/s (VU 50 정점) | 이 경로엔 재고 잠금이 없습니다 |
| Outbox 릴레이 (기본 100/1초) | ~90/s | 설정값이 만든 상한 |
| Outbox 릴레이 (설정 완화 시) | **2,600/s** | 여기가 실제 상한 ([측정 0002](docs/measurements/0002-outbox-relay-batch.md)) |
| 재고 예약 (컨슈머 1) | **~31/s** | 직렬 처리 |
| 재고 예약 (컨슈머 8) | ~24/s | 락 경합 |

**API는 647건/초를 받는데 재고 예약은 31건/초를 소화합니다. 약 21배 격차이고, 그 사이는 대기열에 쌓입니다.**

릴레이는 병목처럼 보였지만 아니었습니다. 설정을 풀면 2,600/s까지 가고, 이는 API보다도 4배 빠릅니다. 다만 **릴레이를 풀어도 end-to-end 처리량은 늘지 않습니다.** 뒤에 31/s가 있어서, 대기열이 `outbox_events` 테이블에서 Kafka로 옮겨갈 뿐입니다. 그래서 기본값 100/1초를 그대로 뒀습니다.

파티션과 컨슈머를 8배로 늘려봤더니 처리량이 늘지 않고 오히려 줄었습니다. 같은 구간에서 InnoDB 행 잠금 대기가 11,662회, 평균 30ms였습니다. 이벤트당 처리 시간이 약 32ms이므로 대부분이 대기입니다. **재고 행 하나가 상한이고, 스레드로 넘을 수 있는 종류가 아닙니다.**

다만 지금 구성(파티션 1·컨슈머 1)에서는 그 행에 동시에 접근하는 스레드가 하나뿐입니다. `SELECT FOR UPDATE`는 다중 인스턴스에 대비한 장치이며 현재 실행 조건에서 경합을 막고 있지는 않습니다.

## 한계

- 위 측정은 단일 개발 장비에서 부하 생성기·애플리케이션·DB가 같은 머신에 있는 조건입니다. 절대값은 환경에 종속되며, 결론으로 삼은 것은 조건을 바꿨을 때의 상대 변화입니다
- 만료 임계값(기본 5분)은 근거 있는 측정값이 아니라 결제 제한 시간으로 정한 정책값입니다. 실제 이탈 분포를 확인하지는 않았습니다
- `PENDING` 상태로 고착된 주문은 만료 대상이 아닙니다. 재고 예약 여부를 order-service가 알 수 없어 안전한 해제가 불가능합니다. 재고를 잠그지 않으므로 피해는 작습니다
- `REFUND_REQUIRED` 주문을 처리하는 운영 도구가 없습니다. 상태로 남길 뿐입니다
- Outbox 릴레이는 1초 주기 polling이라 SAGA 시작까지 최대 1초 지연이 생깁니다. 배치 크기(`outbox.relay.batch-size`)와 폴링 주기(`outbox.relay.fixed-delay`) 모두 설정으로 조절할 수 있지만, 기본값 100/1초가 최적이라는 근거는 아직 없습니다
- PG 승인 호출은 Mock입니다. 연동 흐름은 토스페이먼츠 v2 규격(인증 → `paymentKey` → 서버 승인)을 따르지만 실제 카드사 승인은 일어나지 않습니다
- 승인 API가 타임아웃되면 승인 여부를 알 수 없는데, `paymentKey`로 결제를 조회해 대사하는 경로가 아직 없습니다
- 결제 확인 API에 인증이 없습니다. 금액 위변조는 저장된 금액과의 대조로 막지만, 호출자 검증은 없습니다
- product-service의 상품 생성·재고 조정 API와 member 조회에도 인가가 없습니다. JWT 시크릿도 설정 파일에 있습니다
- PG 승인 호출이 DB 트랜잭션 안에 있습니다. 승인 뒤 커밋이 실패하면 로컬 DB는 롤백되지만 실제 결제는 이미 끝났을 수 있습니다. `PaymentConfirmAtomicityTest`가 보장하는 것은 **로컬 DB의 원자성이지 결제의 원자성이 아닙니다.** 실제 PG라면 결제 시도 상태(`PROCESSING`)와 `paymentKey` 조회 대사가 선행되어야 합니다
- `Order`·`Payment`에 낙관적 락이 없습니다. 재고 쪽은 예약 상태 전이로 순서를 판별하지만 주문 상태는 마지막 커밋이 앞선 결과를 덮을 수 있습니다
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

**SAGA 종단 검증**
주문 생성부터 결제 승인·거절까지 실제 프로세스로 돌려 세 서비스의 상태가 일치하는지 확인했습니다. Mock PG는 `paymentKey`가 `fail_`로 시작하면 거절하므로, 무작위 실패율 없이 보상 경로를 결정적으로 재현할 수 있습니다.

| | 승인 | 거절 | 대기 |
|---|---:|---:|---:|
| k6 보고 | 111 | 49 | 3 |
| `orders` | CONFIRMED 111 | CANCELLED 49 | WAITING_PAYMENT 3 |
| `inventory_reservations` | CONFIRMED 111 | RELEASED 49 | RESERVED 3 |
| `payments` | APPROVED 111 | FAILED 49 | PENDING 3 |

재고 차감량은 확정 건수와 같은 111이고 `reserved_quantity`는 대기 중인 3이었습니다. 예약량이 음수가 되거나 확정 건수와 차감량이 어긋나면 상반된 이벤트가 둘 다 반영된 것입니다. 5xx는 0건이었습니다.

**테스트의 브로커 의존 제거**
DB는 H2로 격리해뒀는데 Kafka는 안 해놔서, 테스트가 개발용 브로커 주소를 그대로 보고 있었습니다. 브로커가 없으면 컨텍스트 기동마다 토픽 프로비저닝 타임아웃을 소진합니다. 통과는 하지만 CI가 33분 걸렸고, 실제 테스트 실행 시간은 전부 1초 미만이었습니다. in-memory 바인더 적용 후 33분 17초에서 19초가 됐습니다.

## 주문 플로우

```
[POST /orders]  Idempotency-Key 필수 · quantity > 0
     │  같은 (member_id, key) 재요청이면 기존 주문을 그대로 반환하고 끝낸다
     ▼
 Order 생성 (PENDING) + outbox_events 저장 (같은 트랜잭션)
     │
     ▼
 OutboxEventRelay ──▶ Kafka: order.created
                             │
                 ┌───────────┘
                 ▼
     Product ── 한 트랜잭션 ────────────────────────┐
       processed_events 멱등 체크                   │
       products FOR UPDATE → reserved += q          │
       inventory_reservations 생성 (RESERVED)  ★    │
       outbox_events 저장                           │
     ─────────────────────────────────────────────┘
                 │
        ┌────────┴────────┐
        ▼                 ▼
  inventory.reserved  inventory.rejected
        │                 │
        ▼                 ▼
  Order: WAITING_PAYMENT  Order: CANCELLED
  Payment: 결제 대기 생성
  (금액·주문정보 확정 저장, PG 호출 없음)
        │
        │   ◀── 여기서 멈춰 구매자를 기다린다
        │       결제창 인증 → paymentKey 발급
        ▼
  [POST /payments/confirm]  paymentKey · orderId · amount
        │
        │   저장된 금액과 대조 후 PG 승인 호출
  ┌─────┴──────┐
  ▼            ▼
payment      payment
.completed   .failed
  │            │
  ▼            ▼
Order:       Order: CANCELLED
CONFIRMED    Product: 예약 RESERVED→RELEASED
Product:              reserved -= q
  예약 RESERVED→CONFIRMED
  stock -= q, reserved -= q

기다리는 동안 인증이 오지 않으면
  ─▶ 스위퍼가 EXPIRED + order.expired ─▶ Product: 예약 RESERVED→RELEASED

만료된 뒤 결제 성공이 도착하면
  ─▶ Order:   EXPIRED → REFUND_REQUIRED   (재고를 되잡지 않고 운영 개입 대상)
  ─▶ Product: 예약이 이미 RELEASED → 전이 불가 → 아무것도 하지 않는다  ★
```

★ 표시가 주문별 예약입니다. 상품의 집계 수량만으로는 "만료 뒤 늦은 결제"와 "확정 뒤 늦은 만료"의 조건이 같아 보여, 둘 다 반영되면 예약량이 음수가 되거나 팔린 재고가 되살아납니다. 전이가 가능할 때만 수량을 움직이므로 이벤트 순서와 무관하게 결과가 같습니다.

서비스 4개(member / order / product / payment)가 각각 독립 프로세스이며 DB도 분리돼 있습니다.
오케스트레이터 없이 이벤트 연쇄로 진행하는 SAGA Choreography입니다.

**결제만 동기입니다.** 실제 PG 승인은 구매자가 결제창에서 카드사 인증을 마쳐야 발급되는 `paymentKey`가 있어야 성립하므로, 서버가 이벤트만 보고 단독으로 시작할 수 있는 절차가 아닙니다. 돈이 나가는 지점은 사용자 의사 확인이 필요하고 결과를 즉시 돌려줘야 해서 동기 API로 두고, 그 앞뒤(재고 예약·주문 확정·보상)는 이벤트로 뒀습니다.

재고를 결제 전에 예약하는 것은 일반 커머스의 기본형이 아닙니다. 대부분은 예약 없이 결제 후 차감하고 오버셀은 사후 취소로 수습합니다. 이 프로젝트는 한정판을 전제해 반대로 갔습니다. 수량이 적으면 오버셀 한 건이 곧 사고이기 때문입니다. 대신 잡은 재고를 놓는 책임이 생겨 만료 스위퍼가 필요해졌습니다.

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
