/**
 * SAGA 정합성 테스트
 *
 * 목적: 주문 생성 후 Kafka 이벤트 흐름(payment.failed → CANCELLED 롤백)이
 *       동시 다발적으로 발생해도 보상 트랜잭션이 누락/중복 없이 처리되는지 검증
 *
 * 시나리오:
 *   - 200명이 동시에 주문 생성
 *   - 각 주문은 Kafka를 통해 payment.failed 이벤트를 받아 CANCELLED 처리됨
 *   - 테스트 후 DB에서 CANCELLED 건수 = 전체 주문 건수인지 확인
 *
 * 주의: payment-service가 실패를 발생시키는 조건이 있어야 함
 *       (예: 특정 금액 이상이면 실패, 또는 테스트용 상품 ID 사용)
 *
 * 실행:
 *   k6 run -e JWT_TOKEN=$JWT_TOKEN -e BASE_URL=http://localhost:8083 -e PRODUCT_ID=1 saga-consistency.js
 *
 * 테스트 후 DB 검증 (약 30초 후 Kafka 처리 완료 대기):
 *   SELECT status, COUNT(*) FROM orders GROUP BY status;
 *   → CANCELLED 건수 = 전체 주문 건수이면 SAGA 정합성 OK
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const ordersCreated = new Counter('orders_created');
const orderCreateFailed = new Counter('order_create_failed');
const orderDuration = new Trend('order_duration_ms');

export const options = {
  scenarios: {
    saga_test: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '5s', target: 200 },   // 5초 안에 200명으로 증가
        { duration: '30s', target: 200 },  // 30초 유지
        { duration: '5s', target: 0 },     // 종료
      ],
    },
  },
  thresholds: {
    // 주문 생성 자체는 성공해야 함 (SAGA 롤백은 비동기로 처리)
    'order_create_failed': ['count<10'],
    'http_req_duration': ['p(95)<3000'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8083';
const PRODUCT_ID = __ENV.PRODUCT_ID || '1';
const JWT_TOKEN = __ENV.JWT_TOKEN;

export default function () {
  if (!JWT_TOKEN) {
    console.error('JWT_TOKEN 환경변수가 설정되지 않았습니다.');
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

  const isSuccess = res.status === 200 || res.status === 201;

  check(res, {
    '주문 생성 성공 (2xx)': (r) => r.status === 200 || r.status === 201,
    '5xx 에러 없음': (r) => r.status < 500,
  });

  if (isSuccess) {
    ordersCreated.add(1);
  } else {
    orderCreateFailed.add(1);
    if (res.status >= 500) {
      console.error(`[5xx] status=${res.status} body=${res.body}`);
    }
  }

  // 요청 간 짧은 간격 — 실제 사용자 패턴 모사
  sleep(0.1);
}

export function handleSummary(data) {
  const created = data.metrics['orders_created'] ? data.metrics['orders_created'].values.count : 0;
  const failed = data.metrics['order_create_failed'] ? data.metrics['order_create_failed'].values.count : 0;

  console.log('\n========== SAGA 정합성 테스트 결과 ==========');
  console.log(`주문 생성 성공: ${created}건`);
  console.log(`주문 생성 실패: ${failed}건`);
  console.log('');
  console.log('[ Kafka 처리 완료 후 (약 30초 대기) DB 검증 쿼리 ]');
  console.log('SELECT status, COUNT(*) as count FROM orders GROUP BY status;');
  console.log('');
  console.log('기대 결과:');
  console.log('  - payment.failed 발생 시 → CANCELLED 건수 = 주문 생성 성공 건수');
  console.log('  - CANCELLED 누락 건수가 있으면 보상 트랜잭션 버그 존재!');
  console.log('  - CANCELLED 중복 건수가 있으면 멱등성 처리 버그 존재!');
  console.log('==============================================\n');

  return {
    stdout: JSON.stringify(data, null, 2),
  };
}
