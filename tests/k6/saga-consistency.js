/**
 * SAGA 정합성 테스트 — 주문 생성부터 결제 승인·거절까지 종단으로 돈다.
 *
 * 이전 버전은 주문만 만들고 끝나서 SAGA가 WAITING_PAYMENT 앞에서 멈췄다. 보상 경로를
 * 검증한다고 적혀 있었지만 실제로는 아무것도 확인하지 못했다. 결제 승인이 confirm API로
 * 분리되면서 주문 생성만으로는 결제가 일어나지 않기 때문이다.
 *
 * 흐름:
 *   1. POST /orders                 → 주문 생성 (PENDING)
 *   2. 재고 예약이 비동기로 끝나기를 기다린다 (Outbox 릴레이 1초 폴링 + Kafka)
 *      결제 대기가 생기기 전에는 confirm이 404를 준다.
 *   3. POST /payments/confirm       → 일부는 승인, 일부는 거절
 *      Mock PG는 paymentKey가 'fail_'로 시작하면 거절한다.
 *   4. 승인 → CONFIRMED + 재고 확정 / 거절 → CANCELLED + 예약 해제
 *
 * 실행:
 *   k6 run -e JWT_TOKEN=$JWT_TOKEN -e ORDER_URL=http://localhost:8083 \
 *          -e PAYMENT_URL=http://localhost:8084 -e PRODUCT_ID=1 -e FAIL_RATIO=0.3 \
 *          saga-consistency.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const ordersCreated = new Counter('orders_created');
const orderCreateFailed = new Counter('order_create_failed');
const confirmApproved = new Counter('confirm_approved');
const confirmRejected = new Counter('confirm_rejected');
const confirmNotReady = new Counter('confirm_not_ready');
const serverErrors = new Counter('server_errors');
const reserveWait = new Trend('reserve_wait_ms');

export const options = {
  scenarios: {
    saga_test: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '5s', target: 50 },
        { duration: '30s', target: 50 },
        { duration: '5s', target: 0 },
      ],
      gracefulStop: '30s',
    },
  },
  thresholds: {
    server_errors: ['count<10'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'max'],
};

const ORDER_URL = __ENV.ORDER_URL || __ENV.BASE_URL || 'http://localhost:8083';
const PAYMENT_URL = __ENV.PAYMENT_URL || 'http://localhost:8084';
const PRODUCT_ID = parseInt(__ENV.PRODUCT_ID || '1');
const FAIL_RATIO = parseFloat(__ENV.FAIL_RATIO || '0.3');
const JWT_TOKEN = __ENV.JWT_TOKEN;

// 결제 대기가 생기기까지 기다리는 상한. Outbox 릴레이 1초 폴링 + Kafka 왕복을 감안한다.
const CONFIRM_MAX_ATTEMPTS = 15;
const CONFIRM_RETRY_SEC = 0.5;

export default function () {
  if (!JWT_TOKEN) {
    console.error('JWT_TOKEN 환경변수가 설정되지 않았습니다.');
    return;
  }

  // 1. 주문 생성
  const created = http.post(
    `${ORDER_URL}/api/v1/orders`,
    JSON.stringify({ productId: PRODUCT_ID, quantity: 1 }),
    {
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${JWT_TOKEN}`,
        // 부하 테스트는 매 요청이 새 주문이어야 하므로 반복마다 새 키를 만든다.
        'Idempotency-Key': `${__VU}-${__ITER}-${Date.now()}`,
      },
      timeout: '10s',
    },
  );

  check(created, { '주문 생성 2xx': (r) => r.status === 200 || r.status === 201 });

  if (created.status >= 500) {
    serverErrors.add(1);
  }
  if (created.status !== 200 && created.status !== 201) {
    orderCreateFailed.add(1);
    return;
  }
  ordersCreated.add(1);

  const body = created.json();
  const orderId = body.data.id;
  const amount = body.data.totalPrice;

  // 이 반복이 승인될지 거절될지를 paymentKey 접두사로 정한다. Mock PG가 이 접두사를 보고 거절한다.
  const willFail = Math.random() < FAIL_RATIO;
  const paymentKey = `${willFail ? 'fail' : 'pay'}_${__VU}_${__ITER}`;

  // 2~3. 재고 예약이 끝나 결제 대기가 생길 때까지 기다렸다가 승인 요청
  const waitStart = Date.now();
  let confirmed = null;
  for (let i = 0; i < CONFIRM_MAX_ATTEMPTS; i++) {
    confirmed = http.post(
      `${PAYMENT_URL}/api/v1/payments/confirm`,
      JSON.stringify({ orderId, paymentKey, amount }),
      { headers: { 'Content-Type': 'application/json' }, timeout: '10s' },
    );
    // 404는 아직 재고 예약이 끝나지 않았다는 뜻이다. 실패가 아니라 대기다.
    if (confirmed.status !== 404) {
      break;
    }
    sleep(CONFIRM_RETRY_SEC);
  }
  reserveWait.add(Date.now() - waitStart);

  if (confirmed.status >= 500) {
    serverErrors.add(1);
    console.error(`[5xx] confirm status=${confirmed.status} body=${confirmed.body}`);
  } else if (confirmed.status === 404) {
    confirmNotReady.add(1);
  } else if (willFail) {
    confirmRejected.add(1);
  } else {
    confirmApproved.add(1);
  }
}

export function handleSummary(data) {
  const n = (k) => (data.metrics[k] ? data.metrics[k].values.count : 0);

  console.log('\n========== SAGA 정합성 테스트 결과 ==========');
  console.log(`주문 생성 성공   : ${n('orders_created')}건`);
  console.log(`주문 생성 실패   : ${n('order_create_failed')}건`);
  console.log(`승인 요청(성공)  : ${n('confirm_approved')}건  → CONFIRMED 기대`);
  console.log(`승인 요청(거절)  : ${n('confirm_rejected')}건  → CANCELLED 기대`);
  console.log(`예약 대기 초과   : ${n('confirm_not_ready')}건  (승인까지 못 감)`);
  console.log(`서버 에러(5xx)   : ${n('server_errors')}건`);
  console.log('');
  console.log('[ Kafka 처리가 끝난 뒤 DB 검증 ]');
  console.log('-- 주문 상태 분포');
  console.log('SELECT status, COUNT(*) FROM orders GROUP BY status;');
  console.log('   CONFIRMED = 승인 건수, CANCELLED = 거절 건수여야 한다.');
  console.log('   EXPIRED가 있으면 예약 대기 초과분이 스위퍼에 회수된 것이다.');
  console.log('');
  console.log('-- 재고 정합성 (예약이 남아 있으면 안 된다)');
  console.log('SELECT stock_quantity, reserved_quantity FROM products WHERE id = ' + PRODUCT_ID + ';');
  console.log('   reserved_quantity = 0, 차감량 = CONFIRMED 건수여야 한다.');
  console.log('   음수가 나오면 상반된 이벤트가 둘 다 반영된 것이다.');
  console.log('');
  console.log('-- 예약 상태 (product_db)');
  console.log('SELECT status, COUNT(*) FROM inventory_reservations GROUP BY status;');
  console.log('   RESERVED가 남아 있으면 종결되지 않은 주문이다.');
  console.log('==============================================\n');

  return { stdout: JSON.stringify(data, null, 2) };
}
