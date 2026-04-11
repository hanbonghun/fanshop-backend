# k6 부하 테스트

## 사전 준비

### 1. k6 설치
```bash
brew install k6
```

### 2. JWT 토큰 생성
k6는 JWT를 직접 생성할 수 없으므로, 아래 스크립트로 테스트용 토큰을 미리 생성합니다.

```bash
# generate-token.sh 실행 (Node.js 필요)
cd tests/k6
chmod +x generate-token.sh
./generate-token.sh
```

또는 애플리케이션 실행 후 로그인 API로 토큰을 발급받아 환경변수에 설정합니다.

### 3. 환경변수 설정
```bash
export JWT_TOKEN="<발급받은 토큰>"
export BASE_URL="http://localhost:8083"   # order-service
export PRODUCT_URL="http://localhost:8081" # product-service
export PRODUCT_ID="1"                      # 테스트할 상품 ID
```

---

## 테스트 시나리오

### 1. 재고 동시성 테스트 (`concurrent-order.js`)
- **목적**: 재고 100개 상품에 500명이 동시 주문 시 정확히 100건만 성공하는지 검증
- **핵심 검증**: 낙관적 락 / 비관적 락 없을 경우 재고 초과 주문 발생 여부 확인

```bash
k6 run \
  -e JWT_TOKEN=$JWT_TOKEN \
  -e BASE_URL=$BASE_URL \
  -e PRODUCT_ID=$PRODUCT_ID \
  tests/k6/concurrent-order.js
```

### 2. SAGA 정합성 테스트 (`saga-consistency.js`)
- **목적**: payment.failed 이벤트 발생 시 Order 상태가 CANCELLED로 정확히 롤백되는지 검증
- **핵심 검증**: 보상 트랜잭션 누락 / 중복 처리 여부 확인

```bash
k6 run \
  -e JWT_TOKEN=$JWT_TOKEN \
  -e BASE_URL=$BASE_URL \
  -e PRODUCT_ID=$PRODUCT_ID \
  tests/k6/saga-consistency.js
```

### 3. 스파이크 부하 테스트 (`spike-load.js`)
- **목적**: 컴백/한정판 드롭 시나리오 — 10초 안에 1000명 급증 후 유지
- **핵심 검증**: p95 응답시간, 에러율, 처리량(RPS)

```bash
k6 run \
  -e JWT_TOKEN=$JWT_TOKEN \
  -e BASE_URL=$BASE_URL \
  -e PRODUCT_ID=$PRODUCT_ID \
  tests/k6/spike-load.js
```

---

## 테스트 후 정합성 DB 검증

```sql
-- 1. 재고 초과 주문 여부 (이 값이 초기 재고를 넘으면 동시성 버그)
SELECT COUNT(*) as success_count
FROM orders
WHERE product_id = 1
  AND status NOT IN ('CANCELLED', 'PAYMENT_FAILED');

-- 2. SAGA 보상 트랜잭션 검증
SELECT status, COUNT(*) as count
FROM orders
GROUP BY status;

-- 3. 재고 현황 확인
SELECT stock_quantity, reserved_quantity, (stock_quantity - reserved_quantity) as available
FROM products
WHERE id = 1;
```

---

## Grafana 연동 (실시간 모니터링)

k6 결과를 Prometheus로 전송하려면:

```bash
k6 run \
  --out experimental-prometheus-rw \
  -e K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write \
  -e JWT_TOKEN=$JWT_TOKEN \
  tests/k6/spike-load.js
```
