# Outbox Pattern 확장 설계

- 작성일: 2026-08-15
- 대상: product-service, payment-service (신규 적용) / order-service (기존 개선)

## 배경

order-service에만 Outbox Pattern이 적용되어 있다. product-service와 payment-service는
비즈니스 처리 직후 `StreamBridge.send()`로 즉시 발행하며, 반환값도 검사하지 않는다.

```java
// StockEventPublisher — 발행 실패해도 "Published" 로그가 찍힌다
streamBridge.send(INVENTORY_RESERVED_BINDING, event);
log.info("Published inventory.reserved — orderId={}", event.orderId());
```

| | Outbox | send() 반환값 검사 | producer sync |
|---|---|---|---|
| order-service | 있음 | 있음 | 있음 |
| product-service | 없음 | 없음 | 없음 |
| payment-service | 없음 | 없음 | 없음 |

### 이로 인한 실패 시나리오

payment-service가 `inventory.reserved`를 소비한 뒤 결과 이벤트를 발행하지 못하는 경로가 둘 있다.

**경로 A — 처리 중 예외**
`processPayment()`에서 예외 발생 → 바인더 3회 재시도 → DLQ 격리.
메시지는 DLQ에 안전히 보관되지만 order-service는 그 사실을 모른다.

**경로 B — 발행 직전 크래시**
결제가 APPROVED로 커밋된 뒤 발행 직전에 프로세스 종료. 재시작 후 재소비하면
`existsByOrderId`가 true라 `AlreadyProcessed`를 반환하고 **로그만 남기고 정상 종료**한다.
DLQ도 거치지 않는다.

두 경로 모두 결과는 같다. order-service는 `WAITING_PAYMENT`에 영구 고착되고,
product-service의 `reservedQuantity`는 영원히 해제되지 않는다. 한정판 상품이라면
그 수량만큼 다시는 판매되지 않는다.

## 목표

- **경로 B 제거** — 비즈니스 처리와 이벤트 발행 예약을 원자적으로 만든다
- **처리 유실 버그 수정** — 멱등성 기록이 비즈니스 처리보다 먼저 커밋되는 문제
- **발행 실패 가시화** — `send()` 반환값 검사와 `sync: true`를 3개 서비스에 통일

## 비목표

- **경로 A는 이 작업 범위가 아니다.** DLQ로 격리된 이벤트를 알아채는 장치(예약 만료 스위퍼)는
  후속 작업으로 분리한다. Outbox로는 해결되지 않는다.
- 재고 선점 방식 변경(Redis), 주문 플로우 순서 재배치, `createOrder`의 트랜잭션 내
  동기 HTTP 호출 제거 — 모두 별도 작업

## 설계 결정

### 결정 1 — 공통 모듈로 추출하지 않고 서비스별로 복제한다

**근거**

- Outbox의 존재 이유가 "비즈니스 데이터와 같은 트랜잭션에 저장"이다. DB가 서비스별로
  분리되어 있어(`order_db` / `product_db` / `payment_db`) 공통 모듈로 빼도 테이블은 3개이고
  각 서비스의 persistence unit에 매핑되어야 한다. 공통화로 줄어드는 것이 적다.
- 릴레이는 "타입 문자열 → 역직렬화 클래스 → 발행 바인딩" 매핑을 알아야 한다. 공통화하려면
  레지스트리나 전략 패턴이 필요한데 서비스당 이벤트가 2개뿐이다. 추상화 비용이 중복 비용보다 크다.
- `support:logging` / `support:monitoring`은 순수 인프라 설정이라 공유가 자연스럽지만,
  Outbox는 JPA 엔티티를 포함한다. 공유 라이브러리에 도메인 데이터 모델이 들어가면 한 서비스의
  스키마 변경이 다른 서비스를 끌고 간다.

**감수하는 것** — 릴레이 로직(재시도·격리)이 3벌 중복된다. 애초 추정은 약 70줄, 변경 빈도가 낮다는
것이었다. 완성된 브랜치 기준으로 다시 재보니 둘 다 틀렸다.

실제 중복은 프로덕션 코드 약 200줄, 테스트 코드 약 350줄이다. 변경 빈도도 낮지 않았다 — 미인식 이벤트
타입을 예외로 격리하는 수정(결정 2)이 이 작업 하나 안에서 두 번의 별도 커밋을 필요로 했다. product에서
발견해 고쳤고(`b61904b`), 원본인 order에 백포트했다(`5a9a656`). payment는 그 사이에 작성되어 이미
고쳐진 형태를 복사했기 때문에 별도 수정이 없었다 — 즉 순서가 조금만 달랐다면 세 번이었다.

결정 자체(공통 모듈로 추출하지 않는다)는 바뀌지 않는다. 근거는 여전히 유효하다. 다만 셋 중 실제로
버틴 근거는 "공유 모듈에 도메인 엔티티가 들어가면 서비스가 결합된다" 하나뿐이었다. "테이블이 같은 DB에
있어야 한다"는 엔티티를 로컬에 두는 근거이지 릴레이 로직까지 복제할 근거가 아니고, "각 릴레이가 자기
이벤트 타입만 안다"는 `publish()` 약 10줄에만 해당한다. 배치 루프·재시도·격리·purge·`OutboxRecorder`는
서비스 고유 로직이 아니다. 나중에 다시 본다면 그 지점이 분리선이 될 것이다.

### 결정 2 — 기존 구현의 약점을 개선한 형태로 통일한다

order-service의 현재 릴레이에는 알려진 약점이 있다. 그대로 복제하면 문제도 3배가 되므로
개선한 뒤 order-service를 포함해 3개 서비스를 같은 형태로 맞춘다.

- PENDING 전체를 한 트랜잭션에 로드 (LIMIT 없음)
- 행 잠금이 없어 다중 인스턴스에서 중복 발행
- PUBLISHED 행이 무한 증가

### 결정 3 — PUBLISHED는 보관 기간을 두고 삭제한다

발행 후 7일이 지난 PUBLISHED 행을 일 1회 삭제한다. 최근 이벤트는 남아 장애 분석이 가능하고
테이블은 일정 크기를 유지한다. **FAILED는 삭제하지 않는다** — 수동 복구 대상이다.

## 트랜잭션 경계

현재 리스너는 멱등성 기록이 별도 트랜잭션으로 먼저 커밋된다.

```java
if (existsBy(...)) return;
save(new ProcessedEvent(...));          // 여기서 커밋
productService.softReserveStock(...);   // 여기서 실패하면?
```

비즈니스 처리가 일시 실패하면 재시도가 와도 `existsBy`가 true라 조용히 스킵된다.
메시지 유실은 아니지만 **처리 유실**이다. 멱등성 가드가 "받았다"를 "처리했다"로 착각한다.

목표 형태는 셋을 한 트랜잭션에 묶는 것이다.

```java
@Transactional
public void handleOrderCreated(OrderCreatedEvent event) {
    if (processedEventRepository.existsBy(...)) return;
    processedEventRepository.save(new ProcessedEvent(...));   // ① 멱등성
    ReservationResult result = productService.softReserveStock(...);  // ② 비즈니스
    outbox.save(result.toOutboxEvent());                      // ③ 발행 예약
}   // 셋이 함께 커밋되거나 함께 롤백된다
```

②가 실패하면 ①도 롤백되므로 재시도가 정상 동작한다.

### 실패를 두 종류로 나눈다

트랜잭션으로 묶으면 예외 처리 방식이 문제가 된다. `CoreException`은 `RuntimeException`이므로
Spring이 트랜잭션을 rollback-only로 마킹한다. 예외를 잡아 보상 이벤트를 Outbox에 저장해도
**커밋되지 않는다.** 재고 부족 주문이 영원히 응답을 받지 못하게 된다.

판단 기준은 **"재시도해서 될 일인가"** 이다.

| 종류 | 예 | 처리 |
|---|---|---|
| 결정적 실패 | 재고 부족, 상품 없음 | 반환값(`Rejected`) → 보상 이벤트 발행 후 커밋 |
| 일시적 실패 | DB 데드락, 커넥션 고갈 | 예외 → 롤백 → 재시도 → 3회 실패 시 DLQ |

재시도해도 성공하지 않을 실패를 예외로 던지면 DLQ로 가고 주문이 영원히 멈춘다.
현재 `softReserveStock`은 재고 부족과 상품 없음을 모두 예외로 던지므로 둘 다 반환값으로 바꾼다.

payment-service는 이미 `PaymentResult` sealed interface로 이 구조를 쓰고 있다.
product-service에 같은 패턴을 적용해 스타일을 통일한다.

```java
sealed interface ReservationResult {
    record Reserved(InventoryReservedEvent event) implements ReservationResult {}
    record Rejected(InventoryRejectedEvent event) implements ReservationResult {}
}
```

`@Transactional(noRollbackFor = CoreException.class)`도 가능하지만, 예외를 흐름 제어로
계속 쓰는 데다 롤백 규칙이 애너테이션에 숨겨져 채택하지 않았다.

### 리스너를 `@Configuration` + 핸들러 `@Component`로 분리한 이유

위 코드 예시는 `@Transactional`이 리스너 메서드에 바로 붙는 것처럼 보이지만, 실제 구현은 그렇게
하지 않았다. `OrderCreatedListener`/`InventoryReservedListener`/`PaymentResultListener`/
`StockResultListener`는 여전히 `@Configuration` 클래스로 남고, `Consumer<T>` `@Bean` 메서드만
제공한다. 트랜잭션 로직은 각각 새로 만든 `OrderCreatedHandler`, `InventoryReservedHandler`,
`PaymentResultHandler`, `StockResultHandler` `@Component`로 옮기고, `@Bean` 메서드는 그 핸들러의
메서드 레퍼런스를 반환한다.

```java
@Configuration
@RequiredArgsConstructor
public class OrderCreatedListener {

    private final OrderCreatedHandler orderCreatedHandler;

    @Bean
    public Consumer<OrderCreatedEvent> orderCreatedConsumer() {
        return orderCreatedHandler::handle;   // 핸들러 빈의 프록시를 거친다
    }

}
```

**왜 리스너에 직접 `@Transactional`을 붙일 수 없는가.** 리스너는 `@Bean` 메서드를 가진
`@Configuration` 클래스이고, Spring Boot는 이런 설정 클래스를 CGLIB으로 프록시해 `@Bean` 메서드
호출을 가로챈다(싱글턴 보장 목적). 그런데 이 프록시는 `@Bean` *메서드 자체의 호출*을 가로채는 것이지,
그 메서드가 반환한 객체의 메서드 호출을 가로채지 않는다. `@Transactional`을 리스너의 `handleOrderCreated`
같은 메서드에 붙이고 `this::handleOrderCreated`를 `@Bean` 메서드에서 참조로 넘기면, 그 메서드
레퍼런스는 프록시되지 않은 `this`를 가리킨다 — 트랜잭션 어드바이저를 절대 거치지 않으므로
애너테이션이 조용히 무시된다(inert). 반면 핸들러는 평범한 `@Component`라 스프링이 통상적인
방식으로 트랜잭션 프록시를 씌우고, `@Bean` 메서드는 그 프록시된 빈의 메서드 레퍼런스를 반환하므로
`@Transactional`이 정상적으로 적용된다.

### 경로 B가 해결되는 지점

Outbox 적용 후에는 결제 승인과 Outbox 저장이 원자적이다. 결제가 저장되었다면 Outbox 행도
반드시 존재하므로 릴레이가 발행한다. `AlreadyProcessed` 분기가 안전해진다.

## 릴레이

```java
@Query(value = """
    SELECT * FROM outbox_events
    WHERE status = 'PENDING'
    ORDER BY created_at
    LIMIT :batchSize
    FOR UPDATE SKIP LOCKED
    """, nativeQuery = true)
List<OutboxEvent> findPendingBatch(@Param("batchSize") int batchSize);
```

- **native query** — JPQL로는 `SKIP LOCKED`를 표현할 수 없다. `@Lock` + `QueryHint` 조합은
  LIMIT과 함께 쓸 때 생성되는 SQL이 불투명하다.
- **배치 크기 100** — 백로그가 쌓여도 한 트랜잭션 크기가 고정된다.
- **인덱스 `(status, created_at)`** — 현재 없다. PENDING을 골라 정렬하는 쿼리라 없으면 풀스캔이다.
- H2 2.4.240이 `FOR UPDATE SKIP LOCKED`를 지원함을 확인했다. 테스트에서 동일 쿼리를 검증할 수 있다.

`SKIP LOCKED`로 릴레이 인스턴스가 여러 개여도 서로 다른 배치를 집는다.

### 정리 스케줄러

```java
@Scheduled(cron = "0 0 3 * * *")
@Transactional
public void purgePublished() {
    int deleted = repository.deleteByStatusAndPublishedAtBefore(
        PUBLISHED, LocalDateTime.now().minusDays(RETENTION_DAYS));
    log.info("Outbox 정리 — 삭제 {}건", deleted);
}
```

## 변경 범위

**신규 — product-service, payment-service 각각**

`outbox/` 패키지: `OutboxEvent`, `OutboxEventStatus`, `OutboxEventRepository`, `OutboxEventRelay`

**product-service**

- `product/service/ReservationResult` 신규 (sealed interface)
- `product/service/ProductService.softReserveStock` 반환 타입 변경
- `messaging/OrderCreatedListener` — `@Configuration` + `@Bean` 배선만 남김
- `messaging/OrderCreatedHandler` 신규 — `@Transactional`, Outbox 저장으로 전환 (실제 트랜잭션 로직)
- `messaging/PaymentResultListener` — `@Configuration` + `@Bean` 배선만 남김
- `messaging/PaymentResultHandler` 신규 — `@Transactional`로 멱등성·재고 확정/해제 원자화
- `messaging/StockEventPublisher` — `send()` 반환값 검사
- `application.yml` — producer `sync: true`

**payment-service**

- `messaging/InventoryReservedListener` — `@Configuration` + `@Bean` 배선만 남김
- `messaging/InventoryReservedHandler` 신규 — `@Transactional`, Outbox 저장으로 전환 (실제 트랜잭션 로직)
- `messaging/PaymentEventPublisher` — `send()` 반환값 검사
- `application.yml` — producer `sync: true`

**order-service (기존 개선)**

- `outbox/OutboxEventRepository` — SKIP LOCKED 배치 쿼리
- `outbox/OutboxEventRelay` — 배치 처리, 정리 스케줄러
- `outbox/OutboxEvent` — 인덱스

## 테스트 전략

TDD로 진행한다. 각 항목은 실패하는 테스트를 먼저 작성한다.

1. **원자성 회귀** — 비즈니스 처리가 일시 실패하면 `ProcessedEvent`도 롤백되고, 재시도 시 정상 처리된다
2. **결정적 실패 경로** — 재고 부족 시 롤백 없이 `INVENTORY_REJECTED`가 Outbox에 저장된다
3. **결제 원자성** — 결제 승인과 Outbox 저장이 함께 커밋된다 (경로 B 해결 증명)
4. **SKIP LOCKED** — 동시 릴레이 2개가 같은 이벤트를 집지 않는다.
   `ProductStockConcurrencyTest`의 스레드 + latch 패턴을 재사용한다
5. **정리** — 보관 기간이 지난 PUBLISHED만 삭제되고 FAILED는 남는다

## 리스크

**기존 테스트가 깨진다.** 의도된 변경이며 함께 수정한다.

- `ProductServiceTest$SoftReserveStock.insufficientStock` / `.notFound` — 예외를 기대하고 있어
  반환값 검증으로 전환
- `OrderCreatedListenerTest` — 즉시 발행(`verify(publisher).publishInventoryReserved(...)`)을
  검증 중이며 Outbox 저장 검증으로 전환

**at-least-once는 그대로다.** 리스너에 `@Transactional`을 붙여도 Kafka 오프셋 커밋과 DB
트랜잭션은 별개다. 중복 수신은 계속 발생하며 멱등성으로 흡수한다. 의도된 설계이지 한계가 아니다.

**대량 purge의 락 시간.** 보관 기간이 지난 행이 한 번에 많이 쌓이면 삭제 트랜잭션이 길어질 수 있다.
초기 규모에서는 문제되지 않으나, 필요 시 삭제에도 배치 크기를 도입한다.

## 후속 작업

이 설계는 "발행이 유실되지 않는가"(safety)만 다룬다. "응답이 오지 않는 것을 누가 알아채는가"
(liveness)는 다루지 않는다. 경로 A와 예약 재고 영구 잠금은 예약 만료 스위퍼가 필요하며
별도 설계로 분리한다.
