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
릴레이가 Kafka 발행 후 상태 갱신 전에 죽으면 같은 이벤트를 재발행할 수 있으므로(at-least-once), 소비자의 멱등 처리를 전제로 설계했다.

**수신 멱등성 ([#20](https://github.com/hanbonghun/fanshop-backend/pull/20))**
Kafka at-least-once delivery와 Outbox 릴레이 재발행으로 같은 메시지가 두 번 올 수 있다.
order/product는 `processed_events(event_id, event_type)` 복합 unique 제약으로, payment는 주문 ID 기준 존재 검사로 중복 수신 차단.

**소비 실패 격리 — DLQ와 Outbox FAILED**
poison message(역직렬화 불가, 처리 중 영구 예외)가 들어오면 기본 동작은 제한 재시도 후 로그만 남기고 오프셋을 넘긴다 — 실패가 어디에도 안 남고 유실된다.
컨슈머는 바인더 재시도(3회, 1초 시작 백오프) 소진 후 `error.<destination>.<group>` DLQ 토픽으로 격리하도록 변경. 실패 원인이 exception 헤더와 함께 토픽에 남아 조회/재투입이 가능하다.
Outbox 릴레이도 같은 원리 적용 — 발행 5회 실패 시 FAILED로 전환해 폴링 대상에서 제외한다. 이전에는 실패 이벤트가 PENDING으로 남아 1초마다 무한 재시도하며 로그를 밀어냈다.
재시도 판정이 성립하려면 발행 실패가 릴레이에 예외로 드러나야 한다. `StreamBridge.send` 반환값을 검사하고 producer `sync: true`로 브로커 ack까지 대기시켜, 비동기 전송 실패가 PUBLISHED로 오기록되는 경로를 막았다.

**분산 추적 연결 ([#17](https://github.com/hanbonghun/fanshop-backend/pull/17))**
Grafana Tempo에서 Order → Payment → Order 흐름이 서비스마다 traceId가 달라 끊기는 문제.
`spring.cloud.stream.kafka.binder.enable-observation: true` 설정으로 Kafka 헤더에 trace context 자동 전파.

**테스트의 브로커 의존 제거**
DB는 테스트용으로 격리해뒀는데(`local` 프로파일 → H2) Kafka는 안 해놔서, 테스트가 개발용 브로커 주소(`localhost:9092`)를 그대로 바라보고 있었다.
브로커가 없으면 컨텍스트 기동 때마다 `KafkaTopicProvisioner`가 토픽을 만들려고 AdminClient 타임아웃을 소진한다 — 테스트는 통과하지만 CI가 33분 걸렸다. 실제 테스트 실행 시간은 전부 1초 미만이고, 나머지는 전부 대기였다.
`spring-cloud-stream-test-binder`(in-memory 바인더)를 테스트에 적용해 브로커 접속 자체를 없앴다. MySQL에 H2를 쓴 것과 같은 처리다. 바인더는 교체 가능한 계층이라 리스너 코드(`Consumer<T>`)는 그대로다.
브로커 없이 전체 테스트 33분 17초 → 19초. 진짜 브로커가 필요한 건 SAGA E2E 검증뿐이고, 그건 테스트가 직접 띄우는 방식으로 남겨뒀다.

**Virtual Threads ([#18](https://github.com/hanbonghun/fanshop-backend/pull/18))**
스파이크 테스트에서 Virtual Threads 적용 후 처리량 13,701건 → 20,378건/30초(+49%), p95 응답시간 2,024ms → 991ms(-51%) 확인.
- 조건: `POST /orders` 단일 API, 0→500 VU 5초 램프 후 20초 유지(총 30초), 재고를 충분히 설정해 전 요청이 DB write + Kafka 발행 경로를 통과. p95는 k6 `http_req_duration` 기준
- 변경은 `spring.threads.virtual.enabled: true` 한 줄이며 HikariCP 풀(기본 10) 등 나머지 조건 동일 — 개선분은 Tomcat 스레드 풀(200) 포화로 인한 큐 대기 제거에서 나왔고, 커넥션 풀이 다음 병목으로 남아 있다

## 한계

- Outbox relay는 1초 주기로 polling하기 때문에 SAGA 시작까지 최대 1초 지연이 생긴다
- 결제 서비스는 실제 PG 연동 없이 성공/실패를 시뮬레이션한다
- 서킷 브레이커 미적용 — 동기 호출(`ProductClient`) 실패 시 빠른 차단이 없다
- DLQ 재처리 자동화 없음 — 격리까지만 하고, DLQ 토픽과 FAILED Outbox의 재투입은 수동이다
- 각 서비스는 별도 프로세스(JVM)로 실행되지만, 다중 노드 클러스터가 아닌 단일 개발 장비에서 검증했다

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
# 테스트 — 인프라 없이 동작한다 (DB는 H2, Kafka는 test-binder)
./gradlew test
```
