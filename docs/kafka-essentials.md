# Kafka 필수 지식 — fanshop-backend 기준

이 프로젝트에서 실제로 쓴 것만 골라 정리했다. Kafka 전체가 아니라 **이 코드를 두고 질문받았을 때 막히면 안 되는 범위**다.

한 문장 요약 — Kafka는 여기서 **SAGA Choreography의 이벤트 버스**다. 서비스 4개가 서로를 직접 호출하지 않고 토픽 5개로 상태 변화를 알린다. 보장 수준은 **at-least-once**이고, 중복은 애플리케이션(멱등성 테이블)이 흡수한다.

---

## 1. 토픽 지도 — 이건 외워야 한다

| 토픽 | 발행 | 구독 (consumer group) |
|---|---|---|
| `order.created` | order-service | product-service |
| `inventory.reserved` | product-service | payment-service, order-service |
| `inventory.rejected` | product-service | order-service |
| `payment.completed` | payment-service | order-service, product-service |
| `payment.failed` | payment-service | order-service, product-service |
| `order.expired` | order-service (스위퍼) | product-service |

DLQ 토픽 이름 규칙: `error.<destination>.<group>` → 예: `error.order.created.product-service`

```
POST /orders
   │
   ▼  order.created
product: 재고 예약
   │
   ├── inventory.rejected ──▶ order: CANCELLED
   │
   └── inventory.reserved ──▶ payment: 결제
                                │
                ┌───────────────┴───────────────┐
                ▼                               ▼
          payment.completed               payment.failed
          order: CONFIRMED                order: CANCELLED
          product: 예약 확정               product: 예약 해제

  ─────── 응답이 아예 안 오는 경우 ───────
  order: WAITING_PAYMENT로 N분 정체
   │
   ▼  order.expired   ← 스위퍼가 만드는 유일한 "안 온 것"의 이벤트
  product: 예약 해제
```

---

## 2. 반드시 아는 기본기

### 토픽 / 파티션 / 오프셋

- **토픽** = 이름 붙은 append-only 로그. **파티션** = 그 로그의 물리적 분할이자 **병렬성의 단위이자 순서 보장의 단위**.
- **오프셋** = 파티션 내 순번. 컨슈머가 "어디까지 읽었는지"를 `__consumer_offsets` 내부 토픽에 기록한다.
- 메시지는 **소비해도 삭제되지 않는다.** retention(시간/용량) 기준으로 지워진다. → 그룹을 다르게 하면 같은 메시지를 여러 서비스가 각자 읽을 수 있고, 리플레이도 가능하다.

### 컨슈머 그룹

- 같은 그룹 안에서는 **파티션이 한 컨슈머에게만** 배정된다 → 경쟁 소비(스케일 아웃).
- 다른 그룹은 같은 메시지를 각자 받는다 → 팬아웃.
- 이 프로젝트: `inventory.reserved`를 payment-service와 order-service가 **서로 다른 group**으로 각각 받는다. 같은 그룹이었다면 둘 중 하나만 받아 SAGA가 끊긴다.
- **가장 흔한 사고** — `group`을 안 적으면 Spring Cloud Stream이 익명 그룹을 만든다. 인스턴스를 2대 띄우면 둘 다 처리해 재고가 두 번 깎인다.

### 리밸런싱

- 그룹 멤버가 들어오거나 나가면 파티션이 재배정되고 그동안 소비가 멈춘다.
- 트리거는 배포뿐 아니라 **`max.poll.interval.ms`(기본 5분) 초과**도 포함된다. 리스너 안에서 오래 끌면 브로커가 죽은 컨슈머로 판단한다.
- 이 프로젝트가 걸릴 수 있는 지점: `maxAttempts: 3` + 백오프 1s→10s는 **리스너 스레드를 블로킹하는 재시도**다. 지금 값에서는 문제없지만 백오프를 키우면 `max.poll.interval.ms`를 넘겨 리밸런싱 루프에 빠질 수 있다.

### 복제 / ISR / acks

- 파티션은 리더 1 + 팔로워 N으로 복제된다. **ISR** = 리더를 충분히 따라잡은 복제본 집합.
- `acks=0` 안 기다림 / `acks=1` 리더만 / `acks=all` ISR 전체.
- 이 프로젝트는 **브로커 1대, replication factor 1** → `acks=all`이어도 실질적으로 `acks=1`이다. 브로커 디스크가 죽으면 데이터는 없다.
- 정직한 답 — "로컬 단일 장비 검증 구성이다. 운영이라면 브로커 3대 + RF 3 + `min.insync.replicas=2`가 최소선이다."

---

## 3. 전달 보장 — 이 프로젝트는 at-least-once

| 수준 | 오프셋 커밋 시점 | 결과 |
|---|---|---|
| at-most-once | 처리 **전** | 유실 가능 |
| **at-least-once** | 처리 **후** | **중복 가능 ← 이 프로젝트** |
| exactly-once | Kafka 트랜잭션(EOS) | Kafka→Kafka 구간에서만 성립 |

**왜 exactly-once를 안 썼는가** (그대로 말할 수 있어야 함)

> 리스너에 `@Transactional`을 붙여도 Kafka 오프셋 커밋과 DB 트랜잭션은 서로 다른 자원이라 원자적으로 묶이지 않는다. 2PC를 쓰지 않는 한 "처리는 됐는데 오프셋 커밋 직전에 죽는" 창이 항상 남는다. 그래서 **중복 수신을 없애려 하지 않고, 받아도 결과가 같도록(멱등)** 설계했다. `processed_events`의 `(event_id, event_type)` 복합 unique가 그 장치다.

주의 — Kafka의 EOS(exactly-once semantics)는 **Kafka 토픽 → Kafka 토픽** 구간(Streams)에서 성립한다. Kafka와 외부 DB 사이에는 성립하지 않는다. 이 둘을 섞어 말하면 바로 걸린다.

---

## 4. 순서 보장 — 이 프로젝트의 진짜 취약점

- Kafka의 순서 보장은 **파티션 단위**다. 토픽 단위가 아니다.
- 같은 키를 넣으면 같은 파티션으로 간다(`hash(key) % partitions`). 키가 없으면 sticky/round-robin으로 흩어진다.
- 이 프로젝트는 `streamBridge.send(binding, event)` — **키를 넣지 않는다.** 파티션 수도 명시하지 않아 바인더가 기본값(1개)으로 토픽을 만든다.
- 즉 **지금 순서가 지켜지는 건 파티션이 1개이기 때문**이다. 처리량을 위해 파티션을 늘리는 순간 같은 주문의 `payment.completed`와 `payment.failed`가 다른 파티션으로 가서 순서가 뒤집힐 수 있다.

해결책은 `orderId`를 메시지 키로 넣는 것이다. 같은 주문의 이벤트는 항상 같은 파티션 → 순서 보장 + 파티션 수만큼 병렬 처리. Spring Cloud Stream에서는 `partitionKeyExpression` 또는 `MessageBuilder`로 `KafkaHeaders.KEY`를 지정한다.

> **"파티션 1개를 전제로 뒀고, 스케일 아웃의 전제 조건이 `orderId` 키 도입"** 이라고 말할 수 있어야 한다. "몰랐다"와 "알고 미뤘다"는 완전히 다르다.

---

## 5. 프로듀서 쪽

- **kafka-clients 4.1.1 기본값**: `acks=all`, `enable.idempotence=true`, `retries=Integer.MAX_VALUE`, `max.in.flight.requests.per.connection=5`. Kafka 3.0부터 기본값이 이미 안전한 쪽이라 따로 손대지 않았다 — 이것도 답변이 된다.
- **`enable.idempotence`가 막는 건 프로듀서 재시도로 인한 브로커 측 중복 저장**이다(PID + 시퀀스 번호). **컨슈머가 두 번 읽는 것과는 다른 문제다.** 이 둘을 섞으면 안 된다.
- **`sync: true`** (이 프로젝트가 명시) — `send()`가 브로커 ack까지 기다린다. 비동기면 실패가 콜백으로만 오고 호출부는 성공한 줄 안다. Outbox 릴레이가 발행 성공/실패로 행 상태를 바꾸려면 동기여야 한다.
- **`send()` 반환값 검사** — 버리면 발행 실패해도 `markPublished()`가 실행되어 유실된다. 그래서 실패 시 예외를 던진다.

```java
if (!streamBridge.send(ORDER_CREATED_BINDING, event)) {
    throw new IllegalStateException("order.created 발행 실패 — orderId=" + event.orderId());
}
```

---

## 6. 컨슈머 쪽

- 오프셋 커밋은 auto-commit이 아니라 **리스너 컨테이너가 관리한다**(spring-kafka가 `enable.auto.commit=false`로 두고 처리 후 커밋). 그래서 처리 실패 시 재소비된다 = at-least-once의 근거.
- `maxAttempts: 3`, `backOffInitialInterval: 1000`, `backOffMaxInterval: 10000` — **블로킹 재시도**다. 같은 스레드에서 잠자며 재시도하므로 그동안 해당 파티션의 뒤 메시지가 막힌다(head-of-line blocking).
- `enableDlq: true` — 재시도 소진 시 `error.<destination>.<group>`으로 보내고 오프셋을 커밋한다. 예외 정보가 헤더(`x-exception-message`, `x-exception-stacktrace`, `x-original-topic` 등)에 실린다.

**DLQ는 만능이 아니다 — 이 프로젝트의 핵심 인사이트**

격리된 메시지가 SAGA의 한 단계였다면 **보상도 완결도 영영 오지 않는다.** 격리는 성공했는데 주문은 미완결로 남는다. 유실(safety)은 막았지만 완결(liveness)은 못 막았다. DLQ를 넣으면서 오히려 새 구멍이 생긴 셈이다. → [11. Liveness](#11-liveness--kafka가-주지-않는-보장)

---

## 7. Outbox Pattern — 왜 Kafka만으로 부족한가

**문제: dual write.** DB 커밋과 Kafka 발행은 서로 다른 자원이다.

```java
tx.commit();                  // 주문 저장됨
kafka.send(orderCreated);     // 여기서 프로세스가 죽으면?
```

→ 주문은 있는데 이벤트가 없다 = 영원히 `PENDING`.

**해결:** 같은 트랜잭션에서 `outbox_events` 테이블에 이벤트를 저장하고, 별도 릴레이가 폴링해 발행한다.

```java
@Transactional
public void handle(OrderCreatedEvent event) {
    processedEventRepository.save(...);      // ① 멱등성 기록
    productService.softReserveStock(...);    // ② 비즈니스 처리
    outboxRecorder.record(...);              // ③ 발행 예약
}   // 셋이 함께 커밋되거나 함께 롤백된다
```

릴레이 (`OutboxEventRelay`):

- 1초 주기 폴링, `SELECT ... FOR UPDATE SKIP LOCKED LIMIT 100` — 다중 인스턴스가 같은 행을 집지 않는다 (MySQL 8.0.1+ 필요, JPQL로는 표현 불가해 native query).
- 5회 실패 시 `FAILED`로 격리해 무한 재시도를 제거한다.
- 연속 3회 실패면 브로커 장애로 판단하고 이번 틱을 중단한다 — 안 그러면 DB 커넥션과 행 잠금을 발행 타임아웃만큼 붙든다.

**대가와 짝**

- Outbox는 **at-least-once를 더 강하게 만든다.** 발행 성공 후 `markPublished()` 직전에 죽으면 재발행된다 → **멱등성이 필수 짝**이다. 이 인과를 설명할 수 있어야 한다.
- 폴링이라 SAGA 시작까지 최대 1초 지연. 대안은 CDC(Debezium binlog tailing).
- 미인식 이벤트 타입은 **예외로 던져야 한다.** 로그만 남기고 정상 반환하면 Kafka에 전달되지 않은 행이 `PUBLISHED`로 기록되어 Outbox의 존재 이유가 무효화된다.

---

## 8. 멱등성 — at-least-once를 흡수하는 장치

- `processed_events(event_id, event_type)` **복합 unique**. 같은 주문에 여러 종류 이벤트가 오므로 `event_id`만으로는 부족하다.
- **가장 아팠던 함정 (실제로 겪은 것)** — 멱등성 기록을 비즈니스 처리보다 **먼저 커밋**하면, 비즈니스가 일시 실패했을 때 재시도가 `existsBy`에서 true를 만나 **조용히 스킵**된다. 메시지 유실이 아니라 **처리 유실**이고, 실패가 아니니 DLQ에도 안 간다.
- 최악의 증상: product의 `handlePaymentFailed`가 스킵되면 `releaseReservation`이 실행되지 않아 **예약 재고가 영구히 잠긴다.** 한정판이면 그 수량은 다시 팔리지 않는다.
- 해결: 멱등성 기록·비즈니스·발행 예약을 **한 트랜잭션**으로 묶었다.

**파생 결정 — 실패를 두 종류로 나눈다**

| 종류 | 예 | 처리 |
|---|---|---|
| 결정적 실패 | 재고 부족, 상품 없음 | **반환값**(`sealed interface ReservationResult`) → 보상 이벤트 발행 후 커밋 |
| 일시적 실패 | DB 데드락, 커넥션 고갈 | **예외** → 롤백 → 재시도 → 3회 실패 시 DLQ |

이유: `RuntimeException`을 던지면 Spring이 트랜잭션을 rollback-only로 마킹한다. 예외를 잡아 보상 이벤트를 Outbox에 저장해도 **커밋되지 않는다.** 재시도해도 안 될 실패를 예외로 던지면 주문이 영원히 멈춘다.

---

## 9. Spring Cloud Stream — Kafka API를 직접 안 쓴 이유

- 코드는 `Consumer<T>` / `StreamBridge`만 안다. 브로커 접속은 **바인더**가 담당한다.
- 바인딩 이름 규칙: `<함수이름>-in-0` / `<바인딩이름>-out-0`. `destination`이 실제 토픽 이름이다.
- `spring.cloud.function.definition`에 컨슈머 빈 이름을 등록해야 바인딩된다. 빠뜨리면 **조용히 소비되지 않는다.**

**실제로 얻은 것 — 브로커 없는 테스트**

테스트에 전용 설정이 없어 운영 `application.yml`을 그대로 썼고, 브로커 주소가 프로파일 밖 공통 영역에 있어 테스트도 개발용 브로커를 봤다. 브로커가 없으면 컨텍스트 기동마다 `KafkaTopicProvisioner`가 AdminClient 응답을 기다린다 — 테스트는 통과하지만 **CI가 33분 17초** 걸렸다. 실제 테스트 실행 시간은 전부 1초 미만이었다.

`spring-cloud-stream-test-binder`(in-memory)를 적용해 프로비저닝 단계 자체를 없앴다. **33분 17초 → 19초.** 바인더가 교체 가능한 계층이라 리스너 코드는 그대로다.

**함정 — `@Configuration`의 `@Bean Consumer<T>`에 `@Transactional`은 무시된다**

`@Configuration` 클래스는 CGLIB 프록시가 `@Bean` **메서드 호출**을 가로챈다. 그 메서드가 반환한 객체의 메서드 호출은 가로채지 않는다. `this::handleOrderCreated`를 넘기면 프록시되지 않은 `this`를 가리켜 트랜잭션 어드바이저를 절대 거치지 않는다. 그래서 리스너(`@Configuration`)와 핸들러(`@Component`)를 분리했다.

---

## 10. 관측성

- `spring.cloud.stream.kafka.binder.enable-observation: true` → Kafka 헤더에 W3C trace context(`traceparent`)를 실어 서비스 간 traceId를 잇는다.
- 없으면 Tempo에서 Order → Payment → Order가 **서로 다른 트레이스로 끊긴다.**
- 비동기 메시징은 동기 호출과 달리 **호출 스택이 없다.** 헤더 전파가 유일한 연결 고리다.

---

## 11. Liveness — Kafka가 주지 않는 보장

**앞의 장치들이 전부 "온 메시지"만 다룬다는 걸 알아야 한다.**

| 장치 | 막는 것 |
|---|---|
| Outbox | 발행 유실 |
| `processed_events` | 중복 수신 / 처리 유실 |
| 재시도 + DLQ | 소비 실패의 유실 |

셋 다 **safety**(잘못된 일이 일어나지 않음)다. 어느 것도 **liveness**(언젠가 진행됨)를 보장하지 않는다.

**Kafka는 "메시지가 안 왔다"를 알려주지 않는다.** 브로커는 발행된 것만 다루므로, payment-service가 죽거나 메시지가 DLQ로 격리되면 **아무것도 유실되지 않았는데도** 주문은 `WAITING_PAYMENT`에 남고 예약 재고는 잠긴 채다. 이건 메시징의 구조적 성질이지 설정으로 해결되는 게 아니다.

**해결 — 타임아웃 스위퍼는 애플리케이션 책임이다** (`OrderExpirySweeper`)

```java
@Scheduled(fixedDelayString = "${order.expiry.fixed-delay:60000}")
@Transactional
public void sweep() {
    expireBefore(LocalDateTime.now().minusMinutes(thresholdMinutes)); // 기본 5분
}
```

- `WAITING_PAYMENT`로 N분 이상 정체된 주문을 찾아 만료시키고 `order.expired`를 Outbox에 기록 → product-service가 받아 `releaseReservation`.
- **상태 전이와 이벤트 기록이 한 트랜잭션.** 만료시켜놓고 이벤트를 못 남기면 재고가 영원히 잠긴다.
- **`PENDING`은 대상이 아니다.** 재고가 예약됐는지 order-service가 알 수 없어 안전한 해제가 불가능하고, 재고를 잠그지 않으므로 피해도 작다.
- 임계값 트레이드오프: 짧으면 정상 주문을 죽이고, 길면 재고가 그만큼 잠긴다.

> 면접에서 강한 답 — "메시징에서 safety와 liveness는 다른 문제다. Outbox·멱등성·DLQ는 전부 safety고, '응답이 안 오는 것'은 그 어느 것도 못 잡는다. 타임아웃 기반 스위퍼가 필요했다."

---

## 12. 인프라 — 물어보면 답해야 하는 것

- docker-compose가 **ZooKeeper**를 쓴다(cp-kafka 7.6.0). Kafka 3.5부터 ZK는 deprecated고 **4.0에서 제거**됐다 — KRaft가 메타데이터를 Kafka 자신의 로그로 관리한다. 정직한 답: "로컬 검증용 이미지 구성을 그대로 뒀고, 운영이면 KRaft로 간다."
- 브로커 1대 / RF 1 / 파티션 1 — **로컬 단일 장비 검증 구성**이다. 운영 구성인 척하면 안 된다.
- 클라이언트는 kafka-clients 4.1.1 (spring-kafka 4.0.0 / spring-cloud-stream 5.0.0 / Spring Boot 4.0.0).

---

## 13. 예상 질문 & 답변

**Q. 왜 Kafka인가? RabbitMQ는 안 되나?**
SAGA Choreography에서는 한 이벤트를 여러 서비스가 각자 소비해야 한다(`inventory.reserved` → payment + order). Kafka는 컨슈머 그룹별로 오프셋을 따로 들고 있어 팬아웃이 자연스럽고, 소비해도 로그가 남아 재처리·리플레이가 가능하다. 큐는 소비하면 사라진다.

**Q. 메시지 유실은 어떻게 막았나?**
구간을 셋으로 나눠서 답한다. **발행 구간** — Outbox로 DB 커밋과 원자화 + `sync: true` + `send()` 반환값 검사. **전달 구간** — Kafka 복제와 `acks=all`(단, 이 환경은 RF 1이라 실질 보장은 제한적). **소비 구간** — 처리 후 오프셋 커밋 + 재시도 3회 + DLQ 격리.

**Q. 중복은?**
at-least-once를 전제로 두고 `processed_events` 복합 unique로 흡수한다. 핵심은 테이블이 아니라 **트랜잭션 경계**였다 — 멱등성 기록이 먼저 커밋되면 처리 유실이 생긴다.

**Q. 순서는 보장되나?**
지금은 파티션이 1개라 보장된다. 파티션을 늘리는 순간 깨지므로 `orderId` 키 도입이 스케일 아웃의 전제 조건이다.

**Q. 컨슈머 인스턴스를 늘리면 처리량이 늘어나나?**
같은 그룹이면 **파티션 수까지만** 병렬화된다. 파티션이 1개면 인스턴스를 늘려도 실제 소비자는 1개다. 반면 Outbox 릴레이는 `SKIP LOCKED` 덕분에 이미 다중 인스턴스 안전하다.

**Q. 처리 못 한 메시지는 어떻게 되나?**
3회 재시도 후 `error.<topic>.<group>`으로 격리된다. 다만 **격리가 SAGA를 완결시키지는 않는다** — 그래서 별도로 만료 스위퍼가 필요했다.

**Q. payment-service가 통째로 죽으면?**
Kafka는 "메시지가 안 왔다"를 알려주지 않으므로 Outbox·멱등성·DLQ 어느 것도 이 상황을 못 잡는다(전부 safety 장치). `OrderExpirySweeper`가 `WAITING_PAYMENT`로 5분 이상 정체된 주문을 만료시키고 `order.expired`를 발행해 예약 재고를 해제한다. **liveness는 애플리케이션이 타임아웃으로 직접 만들어야 한다.**

**Q. 이 설계에 남은 한계는?**
DLQ 재처리가 수동이다(격리까지만 하고 재투입은 사람이 한다). 파티션 키가 없어 스케일 아웃 시 순서가 깨진다. 브로커 1대·RF 1이라 브로커 장애에는 무방비다. Outbox 릴레이가 1초 폴링이라 SAGA 시작까지 최대 1초 지연이 있다.

**Q. 리스너에 `@Transactional`을 붙이면 exactly-once 아닌가?**
아니다. DB 트랜잭션과 Kafka 오프셋 커밋은 별개 자원이라 함께 롤백되지 않는다. `@Transactional`은 **DB 쪽 작업들끼리의 원자성**을 보장할 뿐이고, 중복 수신은 그대로 발생한다.

---

## 관련 문서

- [ADR 0001 — Outbox Pattern 확장](adr/0001-outbox-expansion.md)
- [README](../README.md) — 주문 플로우와 정합성 이슈 전체
