/**
 * 재고 동시성 테스트
 *
 * 목적: 재고 100개 상품에 500명이 동시 주문 시 정확히 100건만 성공하는지 검증
 * 핵심: 낙관적 락(@Version) 없으면 재고 초과 주문 발생 → 이 테스트로 버그 재현
 *
 * 실행 전:
 *   1. DB에 재고 100개짜리 상품 INSERT (productId=1)
 *   2. JWT 토큰 생성: ./generate-token.sh 1
 *   3. 환경변수 설정 후 실행:
 *      k6 run -e JWT_TOKEN=$JWT_TOKEN -e BASE_URL=http://localhost:8083 -e PRODUCT_ID=1 concurrent-order.js
 *
 * 테스트 후 DB 검증:
 *   SELECT COUNT(*) FROM orders WHERE product_id = 1 AND status NOT IN ('CANCELLED', 'PAYMENT_FAILED');
 *   → 이 값이 100을 초과하면 동시성 버그 존재
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// 커스텀 메트릭
const successOrders = new Counter('success_orders');   // 주문 성공 건수
const failedOrders = new Counter('failed_orders');     // 주문 실패 건수 (재고 부족 포함)
const serverErrors = new Counter('server_errors');     // 5xx 에러 건수 (버그 지표)
const orderDuration = new Trend('order_duration_ms');  // 주문 응답시간

export const options = {
  scenarios: {
    // 500명이 동시에 몰리는 순간 집중 시나리오
    concurrent_spike: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '5s', target: 500 },   // 5초 안에 500명으로 급증
        { duration: '20s', target: 500 },  // 20초 유지 (재고 소진될 때까지)
        { duration: '5s', target: 0 },     // 종료
      ],
    },
  },
  thresholds: {
    // 재고 소진 후 실패는 정상이므로 5xx만 엄격하게 체크
    'server_errors': ['count<5'],
    // 응답시간: 95%가 3초 이내
    'http_req_duration': ['p(95)<3000'],
    // 주문 성공 건수는 재고(100개)를 초과하면 안 됨 (정합성 검증은 DB에서)
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8083';
const PRODUCT_ID = __ENV.PRODUCT_ID || '1';
const JWT_TOKEN = __ENV.JWT_TOKEN;

export default function () {
  if (!JWT_TOKEN) {
    console.error('JWT_TOKEN 환경변수가 설정되지 않았습니다. generate-token.sh를 먼저 실행하세요.');
    return;
  }

  const payload = JSON.stringify({
    productId: parseInt(PRODUCT_ID),
    quantity: 1,
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${JWT_TOKEN}`,
    },
    timeout: '10s',
  };

  const start = Date.now();
  const res = http.post(`${BASE_URL}/api/v1/orders`, payload, params);
  orderDuration.add(Date.now() - start);

  // 응답 검증
  const isSuccess = res.status === 200 || res.status === 201;
  const isStockOut = res.status === 409 || res.status === 400; // 재고 부족
  const isServerError = res.status >= 500;

  check(res, {
    '5xx 에러 없음': (r) => r.status < 500,
    '응답이 있음': (r) => r.body !== null && r.body !== '',
  });

  if (isSuccess) {
    successOrders.add(1);
  } else if (isStockOut) {
    failedOrders.add(1);
  } else if (isServerError) {
    serverErrors.add(1);
    console.error(`[5xx] status=${res.status} body=${res.body}`);
  }

  // VU 간 간격 없음 — 최대 동시성 유지
}

export function handleSummary(data) {
  const success = data.metrics['success_orders'] ? data.metrics['success_orders'].values.count : 0;
  const failed = data.metrics['failed_orders'] ? data.metrics['failed_orders'].values.count : 0;
  const errors = data.metrics['server_errors'] ? data.metrics['server_errors'].values.count : 0;

  console.log('\n========== 재고 동시성 테스트 결과 ==========');
  console.log(`주문 성공: ${success}건`);
  console.log(`재고 부족 실패: ${failed}건`);
  console.log(`서버 에러(5xx): ${errors}건`);
  console.log(`총 요청: ${success + failed + errors}건`);
  console.log('');
  console.log('[ DB 정합성 검증 쿼리 ]');
  console.log(`SELECT COUNT(*) FROM orders WHERE product_id = ${PRODUCT_ID} AND status NOT IN ('CANCELLED', 'PAYMENT_FAILED');`);
  console.log('→ 결과가 초기 재고(100)를 초과하면 동시성 버그 존재!');
  console.log('==============================================\n');

  return {
    stdout: JSON.stringify(data, null, 2),
  };
}
