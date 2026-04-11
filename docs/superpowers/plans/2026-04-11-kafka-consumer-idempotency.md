# Kafka Consumer 멱등성 처리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Kafka at-least-once delivery로 인한 중복 이벤트 수신 시 비즈니스 로직이 두 번 실행되지 않도록 ProcessedEvent 테이블 기반 멱등성 처리를 product-service와 order-service에 적용한다.

**Architecture:** `processed_events(event_id, event_type)` 복합 unique 제약 테이블을 각 서비스 DB에 생성. Consumer 진입 시 INSERT 시도 → DataIntegrityViolationException 발생 시 skip. payment-service는 이미 `Payment.orderId unique + existsByOrderId()` 로 처리 완료이므로 대상에서 제외.

**Tech Stack:** Spring Data JPA, JPA `@Table(uniqueConstraints)`, MockitoExtension, AssertJ

---

## 파일 구조

### 새로 생성
- `product-service/src/main/java/com/fanshop/common/idempotency/ProcessedEvent.java`
- `product-service/src/main/java/com/fanshop/common/idempotency/ProcessedEventRepository.java`
- `order-service/src/main/java/com/fanshop/common/idempotency/ProcessedEvent.java`
- `order-service/src/main/java/com/fanshop/common/idempotency/ProcessedEventRepository.java`
- `order-service/src/test/java/com/fanshop/messaging/StockResultListenerTest.java`
- `product-service/src/test/java/com/fanshop/messaging/PaymentResultListenerTest.java`

### 수정
- `product-service/src/main/java/com/fanshop/messaging/OrderCreatedListener.java`
- `product-service/src/main/java/com/fanshop/messaging/PaymentResultListener.java`
- `product-service/src/test/java/com/fanshop/messaging/OrderCreatedListenerTest.java`
- `order-service/src/main/java/com/fanshop/messaging/StockResultListener.java`

---

## Task 1: product-service — ProcessedEvent 엔티티 & 레포지토리 생성

**Files:**
- Create: `product-service/src/main/java/com/fanshop/common/idempotency/ProcessedEvent.java`
- Create: `product-service/src/main/java/com/fanshop/common/idempotency/ProcessedEventRepository.java`

- [ ] **Step 1: ProcessedEvent 엔티티 생성**

```java
// product-service/src/main/java/com/fanshop/common/idempotency/ProcessedEvent.java
package com.fanshop.common.idempotency;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "processed_events",
        uniqueConstraints = @UniqueConstraint(columnNames = { "event_id", "event_type" }))
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false)
    private LocalDateTime processedAt;

    public ProcessedEvent(String eventId, String eventType) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.processedAt = LocalDateTime.now();
    }

}
```

- [ ] **Step 2: ProcessedEventRepository 생성**

```java
// product-service/src/main/java/com/fanshop/common/idempotency/ProcessedEventRepository.java
package com.fanshop.common.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {

    boolean existsByEventIdAndEventType(String eventId, String eventType);

}
```

- [ ] **Step 3: 컨텍스트 테스트 실행으로 엔티티 로딩 확인**

```bash
cd product-service
../gradlew :product-service:test --tests "com.fanshop.ContextTest"
```

Expected: PASS (JPA가 processed_events 테이블 auto-create 확인)

---

## Task 2: product-service — OrderCreatedListener 멱등성 적용 및 테스트

**Files:**
- Modify: `product-service/src/main/java/com/fanshop/messaging/OrderCreatedListener.java`
- Modify: `product-service/src/test/java/com/fanshop/messaging/OrderCreatedListenerTest.java`

- [ ] **Step 1: 실패하는 테스트 먼저 작성**

기존 `OrderCreatedListenerTest.java`에 아래 케이스를 추가한다.

```java
// 클래스 상단 필드에 추가
@Mock
private ProcessedEventRepository processedEventRepository;
// (기존 @InjectMocks 재생성 — ProcessedEventRepository 포함되어야 함)
```

그리고 새 `@Nested` 블록 추가:

```java
@Nested
@DisplayName("handleOrderCreated — 멱등성")
class Idempotency {

    @Test
    @DisplayName("이미 처리된 orderId면 재고 선점 없이 무시한다")
    void alreadyProcessed() {
        // given
        OrderCreatedEvent event = new OrderCreatedEvent(1L, 2L, 3L, 4, 50000L);
        given(processedEventRepository.existsByEventIdAndEventType("1", "ORDER_CREATED"))
            .willReturn(true);

        // when
        orderCreatedListener.handleOrderCreated(event);

        // then
        verify(productService, never()).softReserveStock(any(), anyInt());
        verify(stockEventPublisher, never()).publishInventoryReserved(any());
        verify(stockEventPublisher, never()).publishInventoryRejected(any());
    }

    @Test
    @DisplayName("처음 처리되는 이벤트면 ProcessedEvent를 저장한다")
    void saveProcessedEvent() {
        // given
        OrderCreatedEvent event = new OrderCreatedEvent(1L, 2L, 3L, 4, 50000L);
        given(processedEventRepository.existsByEventIdAndEventType("1", "ORDER_CREATED"))
            .willReturn(false);

        // when
        orderCreatedListener.handleOrderCreated(event);

        // then
        verify(processedEventRepository).save(any(ProcessedEvent.class));
        verify(productService).softReserveStock(3L, 4);
    }

}
```

- [ ] **Step 2: 실패 확인**

```bash
../gradlew :product-service:test --tests "com.fanshop.messaging.OrderCreatedListenerTest"
```

Expected: FAIL (ProcessedEventRepository가 주입되지 않음)

- [ ] **Step 3: OrderCreatedListener에 멱등성 로직 추가**

```java
// product-service/src/main/java/com/fanshop/messaging/OrderCreatedListener.java
package com.fanshop.messaging;

import java.util.function.Consumer;

import com.fanshop.common.idempotency.ProcessedEvent;
import com.fanshop.common.idempotency.ProcessedEventRepository;
import com.fanshop.messaging.event.InventoryRejectedEvent;
import com.fanshop.messaging.event.InventoryReservedEvent;
import com.fanshop.messaging.event.OrderCreatedEvent;
import com.fanshop.product.service.ProductService;
import com.fanshop.support.error.CoreException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class OrderCreatedListener {

    private static final String EVENT_TYPE = "ORDER_CREATED";

    private final ProductService productService;

    private final StockEventPublisher stockEventPublisher;

    private final ProcessedEventRepository processedEventRepository;

    @Bean
    public Consumer<OrderCreatedEvent> orderCreatedConsumer() {
        return this::handleOrderCreated;
    }

    public void handleOrderCreated(OrderCreatedEvent event) {
        String eventId = String.valueOf(event.orderId());
        if (processedEventRepository.existsByEventIdAndEventType(eventId, EVENT_TYPE)) {
            log.warn("중복 이벤트 무시 — type={}, orderId={}", EVENT_TYPE, event.orderId());
            return;
        }
        processedEventRepository.save(new ProcessedEvent(eventId, EVENT_TYPE));

        log.info("Received order.created — orderId={}, productId={}", event.orderId(), event.productId());
        try {
            productService.softReserveStock(event.productId(), event.quantity());
            stockEventPublisher.publishInventoryReserved(new InventoryReservedEvent(event.orderId(), event.memberId(),
                    event.productId(), event.quantity(), event.totalPrice()));
        }
        catch (CoreException e) {
            log.warn("Inventory reservation failed — orderId={}, reason={}", event.orderId(), e.getMessage());
            stockEventPublisher.publishInventoryRejected(new InventoryRejectedEvent(event.orderId(), e.getMessage()));
        }
    }

}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
../gradlew :product-service:test --tests "com.fanshop.messaging.OrderCreatedListenerTest"
```

Expected: PASS (모든 케이스)

- [ ] **Step 5: 커밋**

```bash
git add product-service/src/main/java/com/fanshop/common/idempotency/ \
        product-service/src/main/java/com/fanshop/messaging/OrderCreatedListener.java \
        product-service/src/test/java/com/fanshop/messaging/OrderCreatedListenerTest.java
git commit -m "feat(product): OrderCreatedListener 멱등성 처리 추가 (ProcessedEvent)"
```

---

## Task 3: product-service — PaymentResultListener 멱등성 적용 및 테스트

**Files:**
- Modify: `product-service/src/main/java/com/fanshop/messaging/PaymentResultListener.java`
- Create: `product-service/src/test/java/com/fanshop/messaging/PaymentResultListenerTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
// product-service/src/test/java/com/fanshop/messaging/PaymentResultListenerTest.java
package com.fanshop.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fanshop.common.idempotency.ProcessedEvent;
import com.fanshop.common.idempotency.ProcessedEventRepository;
import com.fanshop.messaging.event.PaymentCompletedEvent;
import com.fanshop.messaging.event.PaymentFailedEvent;
import com.fanshop.product.service.ProductService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentResultListenerTest {

    @Mock
    private ProductService productService;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @InjectMocks
    private PaymentResultListener paymentResultListener;

    @Nested
    @DisplayName("handlePaymentCompleted")
    class HandlePaymentCompleted {

        private final PaymentCompletedEvent event = new PaymentCompletedEvent(1L, 2L, 3L, 1);

        @Test
        @DisplayName("처음 수신 시 재고를 확정한다")
        void firstTime() {
            // given
            given(processedEventRepository.existsByEventIdAndEventType("1", "PAYMENT_COMPLETED"))
                .willReturn(false);

            // when
            paymentResultListener.handlePaymentCompleted(event);

            // then
            verify(processedEventRepository).save(any(ProcessedEvent.class));
            verify(productService).confirmReservation(3L, 1);
        }

        @Test
        @DisplayName("중복 수신 시 재고 확정 없이 무시한다")
        void duplicate() {
            // given
            given(processedEventRepository.existsByEventIdAndEventType("1", "PAYMENT_COMPLETED"))
                .willReturn(true);

            // when
            paymentResultListener.handlePaymentCompleted(event);

            // then
            verify(productService, never()).confirmReservation(anyLong(), anyInt());
        }

    }

    @Nested
    @DisplayName("handlePaymentFailed")
    class HandlePaymentFailed {

        private final PaymentFailedEvent event = new PaymentFailedEvent(1L, 2L, 3L, 1, "잔액 부족");

        @Test
        @DisplayName("처음 수신 시 재고를 복원한다")
        void firstTime() {
            // given
            given(processedEventRepository.existsByEventIdAndEventType("1", "PAYMENT_FAILED"))
                .willReturn(false);

            // when
            paymentResultListener.handlePaymentFailed(event);

            // then
            verify(processedEventRepository).save(any(ProcessedEvent.class));
            verify(productService).releaseReservation(3L, 1);
        }

        @Test
        @DisplayName("중복 수신 시 재고 복원 없이 무시한다")
        void duplicate() {
            // given
            given(processedEventRepository.existsByEventIdAndEventType("1", "PAYMENT_FAILED"))
                .willReturn(true);

            // when
            paymentResultListener.handlePaymentFailed(event);

            // then
            verify(productService, never()).releaseReservation(anyLong(), anyInt());
        }

    }

}
```

- [ ] **Step 2: 실패 확인**

```bash
../gradlew :product-service:test --tests "com.fanshop.messaging.PaymentResultListenerTest"
```

Expected: FAIL (handlePaymentCompleted/handlePaymentFailed 메서드 없음, ProcessedEventRepository 없음)

- [ ] **Step 3: PaymentResultListener 수정**

```java
// product-service/src/main/java/com/fanshop/messaging/PaymentResultListener.java
package com.fanshop.messaging;

import java.util.function.Consumer;

import com.fanshop.common.idempotency.ProcessedEvent;
import com.fanshop.common.idempotency.ProcessedEventRepository;
import com.fanshop.messaging.event.PaymentCompletedEvent;
import com.fanshop.messaging.event.PaymentFailedEvent;
import com.fanshop.product.service.ProductService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class PaymentResultListener {

    private static final String PAYMENT_COMPLETED = "PAYMENT_COMPLETED";

    private static final String PAYMENT_FAILED = "PAYMENT_FAILED";

    private final ProductService productService;

    private final ProcessedEventRepository processedEventRepository;

    @Bean
    public Consumer<PaymentCompletedEvent> paymentCompletedConsumer() {
        return this::handlePaymentCompleted;
    }

    @Bean
    public Consumer<PaymentFailedEvent> paymentFailedConsumer() {
        return this::handlePaymentFailed;
    }

    public void handlePaymentCompleted(PaymentCompletedEvent event) {
        String eventId = String.valueOf(event.orderId());
        if (processedEventRepository.existsByEventIdAndEventType(eventId, PAYMENT_COMPLETED)) {
            log.warn("중복 이벤트 무시 — type={}, orderId={}", PAYMENT_COMPLETED, event.orderId());
            return;
        }
        processedEventRepository.save(new ProcessedEvent(eventId, PAYMENT_COMPLETED));

        log.info("Received payment.completed — orderId={}, productId={}", event.orderId(), event.productId());
        productService.confirmReservation(event.productId(), event.quantity());
    }

    public void handlePaymentFailed(PaymentFailedEvent event) {
        String eventId = String.valueOf(event.orderId());
        if (processedEventRepository.existsByEventIdAndEventType(eventId, PAYMENT_FAILED)) {
            log.warn("중복 이벤트 무시 — type={}, orderId={}", PAYMENT_FAILED, event.orderId());
            return;
        }
        processedEventRepository.save(new ProcessedEvent(eventId, PAYMENT_FAILED));

        log.info("Received payment.failed — orderId={}, productId={}", event.orderId(), event.productId());
        productService.releaseReservation(event.productId(), event.quantity());
    }

}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
../gradlew :product-service:test --tests "com.fanshop.messaging.PaymentResultListenerTest"
```

Expected: PASS

- [ ] **Step 5: product-service 전체 테스트 통과 확인**

```bash
../gradlew :product-service:test
```

Expected: PASS (기존 테스트 포함 전체)

- [ ] **Step 6: 커밋**

```bash
git add product-service/src/main/java/com/fanshop/messaging/PaymentResultListener.java \
        product-service/src/test/java/com/fanshop/messaging/PaymentResultListenerTest.java
git commit -m "feat(product): PaymentResultListener 멱등성 처리 추가 (ProcessedEvent)"
```

---

## Task 4: order-service — ProcessedEvent 엔티티 & 레포지토리 생성

**Files:**
- Create: `order-service/src/main/java/com/fanshop/common/idempotency/ProcessedEvent.java`
- Create: `order-service/src/main/java/com/fanshop/common/idempotency/ProcessedEventRepository.java`

- [ ] **Step 1: ProcessedEvent 엔티티 생성**

product-service와 동일한 구조. order-service는 자체 DB를 가지므로 별도 테이블.

```java
// order-service/src/main/java/com/fanshop/common/idempotency/ProcessedEvent.java
package com.fanshop.common.idempotency;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "processed_events",
        uniqueConstraints = @UniqueConstraint(columnNames = { "event_id", "event_type" }))
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false)
    private LocalDateTime processedAt;

    public ProcessedEvent(String eventId, String eventType) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.processedAt = LocalDateTime.now();
    }

}
```

- [ ] **Step 2: ProcessedEventRepository 생성**

```java
// order-service/src/main/java/com/fanshop/common/idempotency/ProcessedEventRepository.java
package com.fanshop.common.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {

    boolean existsByEventIdAndEventType(String eventId, String eventType);

}
```

- [ ] **Step 3: 컨텍스트 테스트 실행**

```bash
../gradlew :order-service:test --tests "com.fanshop.ContextTest"
```

Expected: PASS

---

## Task 5: order-service — StockResultListener 멱등성 적용 및 테스트

**Files:**
- Modify: `order-service/src/main/java/com/fanshop/messaging/StockResultListener.java`
- Create: `order-service/src/test/java/com/fanshop/messaging/StockResultListenerTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
// order-service/src/test/java/com/fanshop/messaging/StockResultListenerTest.java
package com.fanshop.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fanshop.common.idempotency.ProcessedEvent;
import com.fanshop.common.idempotency.ProcessedEventRepository;
import com.fanshop.messaging.event.InventoryRejectedEvent;
import com.fanshop.messaging.event.InventoryReservedEvent;
import com.fanshop.messaging.event.PaymentCompletedEvent;
import com.fanshop.messaging.event.PaymentFailedEvent;
import com.fanshop.order.service.OrderService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StockResultListenerTest {

    @Mock
    private OrderService orderService;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @InjectMocks
    private StockResultListener stockResultListener;

    @Nested
    @DisplayName("inventoryReservedConsumer 멱등성")
    class InventoryReserved {

        private final InventoryReservedEvent event = new InventoryReservedEvent(1L, 2L, 3L, 1, 50000L);

        @Test
        @DisplayName("처음 수신 시 waitForPayment를 호출한다")
        void firstTime() {
            given(processedEventRepository.existsByEventIdAndEventType("1", "INVENTORY_RESERVED"))
                .willReturn(false);

            stockResultListener.handleInventoryReserved(event);

            verify(processedEventRepository).save(any(ProcessedEvent.class));
            verify(orderService).waitForPayment(1L);
        }

        @Test
        @DisplayName("중복 수신 시 waitForPayment를 호출하지 않는다")
        void duplicate() {
            given(processedEventRepository.existsByEventIdAndEventType("1", "INVENTORY_RESERVED"))
                .willReturn(true);

            stockResultListener.handleInventoryReserved(event);

            verify(orderService, never()).waitForPayment(any());
        }

    }

    @Nested
    @DisplayName("inventoryRejectedConsumer 멱등성")
    class InventoryRejected {

        private final InventoryRejectedEvent event = new InventoryRejectedEvent(1L, "재고 부족");

        @Test
        @DisplayName("처음 수신 시 cancelOrder를 호출한다")
        void firstTime() {
            given(processedEventRepository.existsByEventIdAndEventType("1", "INVENTORY_REJECTED"))
                .willReturn(false);

            stockResultListener.handleInventoryRejected(event);

            verify(processedEventRepository).save(any(ProcessedEvent.class));
            verify(orderService).cancelOrder(1L, "재고 부족");
        }

        @Test
        @DisplayName("중복 수신 시 cancelOrder를 호출하지 않는다")
        void duplicate() {
            given(processedEventRepository.existsByEventIdAndEventType("1", "INVENTORY_REJECTED"))
                .willReturn(true);

            stockResultListener.handleInventoryRejected(event);

            verify(orderService, never()).cancelOrder(any(), any());
        }

    }

    @Nested
    @DisplayName("paymentCompletedConsumer 멱등성")
    class PaymentCompleted {

        private final PaymentCompletedEvent event = new PaymentCompletedEvent(1L, 2L, 3L, 1);

        @Test
        @DisplayName("처음 수신 시 confirmOrder를 호출한다")
        void firstTime() {
            given(processedEventRepository.existsByEventIdAndEventType("1", "PAYMENT_COMPLETED"))
                .willReturn(false);

            stockResultListener.handlePaymentCompleted(event);

            verify(processedEventRepository).save(any(ProcessedEvent.class));
            verify(orderService).confirmOrder(1L);
        }

        @Test
        @DisplayName("중복 수신 시 confirmOrder를 호출하지 않는다")
        void duplicate() {
            given(processedEventRepository.existsByEventIdAndEventType("1", "PAYMENT_COMPLETED"))
                .willReturn(true);

            stockResultListener.handlePaymentCompleted(event);

            verify(orderService, never()).confirmOrder(any());
        }

    }

    @Nested
    @DisplayName("paymentFailedConsumer 멱등성")
    class PaymentFailed {

        private final PaymentFailedEvent event = new PaymentFailedEvent(1L, 2L, 3L, 1, "잔액 부족");

        @Test
        @DisplayName("처음 수신 시 cancelOrder를 호출한다")
        void firstTime() {
            given(processedEventRepository.existsByEventIdAndEventType("1", "PAYMENT_FAILED"))
                .willReturn(false);

            stockResultListener.handlePaymentFailed(event);

            verify(processedEventRepository).save(any(ProcessedEvent.class));
            verify(orderService).cancelOrder(1L, "잔액 부족");
        }

        @Test
        @DisplayName("중복 수신 시 cancelOrder를 호출하지 않는다")
        void duplicate() {
            given(processedEventRepository.existsByEventIdAndEventType("1", "PAYMENT_FAILED"))
                .willReturn(true);

            stockResultListener.handlePaymentFailed(event);

            verify(orderService, never()).cancelOrder(any(), any());
        }

    }

}
```

- [ ] **Step 2: 실패 확인**

```bash
../gradlew :order-service:test --tests "com.fanshop.messaging.StockResultListenerTest"
```

Expected: FAIL

- [ ] **Step 3: StockResultListener 수정**

```java
// order-service/src/main/java/com/fanshop/messaging/StockResultListener.java
package com.fanshop.messaging;

import java.util.function.Consumer;

import com.fanshop.common.idempotency.ProcessedEvent;
import com.fanshop.common.idempotency.ProcessedEventRepository;
import com.fanshop.messaging.event.InventoryRejectedEvent;
import com.fanshop.messaging.event.InventoryReservedEvent;
import com.fanshop.messaging.event.PaymentCompletedEvent;
import com.fanshop.messaging.event.PaymentFailedEvent;
import com.fanshop.order.service.OrderService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class StockResultListener {

    private static final String INVENTORY_RESERVED = "INVENTORY_RESERVED";

    private static final String INVENTORY_REJECTED = "INVENTORY_REJECTED";

    private static final String PAYMENT_COMPLETED = "PAYMENT_COMPLETED";

    private static final String PAYMENT_FAILED = "PAYMENT_FAILED";

    private final OrderService orderService;

    private final ProcessedEventRepository processedEventRepository;

    @Bean
    public Consumer<InventoryReservedEvent> inventoryReservedConsumer() {
        return this::handleInventoryReserved;
    }

    @Bean
    public Consumer<InventoryRejectedEvent> inventoryRejectedConsumer() {
        return this::handleInventoryRejected;
    }

    @Bean
    public Consumer<PaymentCompletedEvent> paymentCompletedConsumer() {
        return this::handlePaymentCompleted;
    }

    @Bean
    public Consumer<PaymentFailedEvent> paymentFailedConsumer() {
        return this::handlePaymentFailed;
    }

    public void handleInventoryReserved(InventoryReservedEvent event) {
        String eventId = String.valueOf(event.orderId());
        if (processedEventRepository.existsByEventIdAndEventType(eventId, INVENTORY_RESERVED)) {
            log.warn("중복 이벤트 무시 — type={}, orderId={}", INVENTORY_RESERVED, event.orderId());
            return;
        }
        processedEventRepository.save(new ProcessedEvent(eventId, INVENTORY_RESERVED));
        log.info("Received inventory.reserved — orderId={}", event.orderId());
        orderService.waitForPayment(event.orderId());
    }

    public void handleInventoryRejected(InventoryRejectedEvent event) {
        String eventId = String.valueOf(event.orderId());
        if (processedEventRepository.existsByEventIdAndEventType(eventId, INVENTORY_REJECTED)) {
            log.warn("중복 이벤트 무시 — type={}, orderId={}", INVENTORY_REJECTED, event.orderId());
            return;
        }
        processedEventRepository.save(new ProcessedEvent(eventId, INVENTORY_REJECTED));
        log.info("Received inventory.rejected — orderId={}, reason={}", event.orderId(), event.reason());
        orderService.cancelOrder(event.orderId(), event.reason());
    }

    public void handlePaymentCompleted(PaymentCompletedEvent event) {
        String eventId = String.valueOf(event.orderId());
        if (processedEventRepository.existsByEventIdAndEventType(eventId, PAYMENT_COMPLETED)) {
            log.warn("중복 이벤트 무시 — type={}, orderId={}", PAYMENT_COMPLETED, event.orderId());
            return;
        }
        processedEventRepository.save(new ProcessedEvent(eventId, PAYMENT_COMPLETED));
        log.info("Received payment.completed — orderId={}", event.orderId());
        orderService.confirmOrder(event.orderId());
    }

    public void handlePaymentFailed(PaymentFailedEvent event) {
        String eventId = String.valueOf(event.orderId());
        if (processedEventRepository.existsByEventIdAndEventType(eventId, PAYMENT_FAILED)) {
            log.warn("중복 이벤트 무시 — type={}, orderId={}", PAYMENT_FAILED, event.orderId());
            return;
        }
        processedEventRepository.save(new ProcessedEvent(eventId, PAYMENT_FAILED));
        log.info("Received payment.failed — orderId={}, reason={}", event.orderId(), event.reason());
        orderService.cancelOrder(event.orderId(), event.reason());
    }

}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
../gradlew :order-service:test --tests "com.fanshop.messaging.StockResultListenerTest"
```

Expected: PASS

- [ ] **Step 5: order-service 전체 테스트 통과 확인**

```bash
../gradlew :order-service:test
```

Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add order-service/src/main/java/com/fanshop/common/idempotency/ \
        order-service/src/main/java/com/fanshop/messaging/StockResultListener.java \
        order-service/src/test/java/com/fanshop/messaging/StockResultListenerTest.java
git commit -m "feat(order): StockResultListener 멱등성 처리 추가 (ProcessedEvent)"
```

---

## Task 6: 포맷 & 전체 검증

- [ ] **Step 1: 포맷 자동 적용**

```bash
../gradlew format
```

- [ ] **Step 2: 포맷 검증**

```bash
../gradlew checkFormat
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 전체 테스트**

```bash
../gradlew :product-service:test :order-service:test :payment-service:test
```

Expected: 전체 PASS

- [ ] **Step 4: CLAUDE.md / AGENTS.md Decision Log 추가**

두 파일의 구현 일지에 아래 항목 추가:

```
### [2026-04-11] Kafka Consumer 멱등성 — ProcessedEvent 테이블
**상황:** Kafka at-least-once delivery로 인해 Consumer가 동일 이벤트를 두 번 받을 경우 재고 이중 차감, 결제 중복 처리 위험 존재
**고민:**
- DB unique constraint (Payment에 이미 적용): Payment만 보호, UPDATE 계열 중복 막지 못함
- ProcessedEvent 테이블: 전 서비스 일괄 적용 가능, 추가 인프라 불필요
- Redis TTL: 빠르지만 Redis 의존성 추가 필요
**결정:** ProcessedEvent 테이블 방식 채택. `processed_events(event_id, event_type)` 복합 unique 제약으로 중복 차단
**이유:** MySQL만으로 해결 가능하고, 모든 Consumer에 동일 패턴으로 적용 가능. 다음 작업인 Outbox Pattern과 개념적으로 대칭 (발행 신뢰성 ↔ 수신 멱등성)
**결과:** product-service(OrderCreatedListener, PaymentResultListener), order-service(StockResultListener) 적용 완료. payment-service는 기존 existsByOrderId 유지
```

- [ ] **Step 5: 최종 커밋**

```bash
git add CLAUDE.md AGENTS.md
git commit -m "docs: Kafka Consumer 멱등성 처리 결정 사항 기록"
```
