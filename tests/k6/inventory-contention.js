/**
 * 단일 상품 경합 측정 — 재고가 소진되지 않는 조건에서 처리량 상한을 찾는다.
 *
 * 기존 concurrent-order.js와 목적이 다르다. 그쪽은 "재고 100개에 500명이 몰리면 정확히 100건만
 * 성공하는가"라는 정합성 검증이고, 이 스크립트는 "정합성이 지켜지는 이 구조가 초당 몇 건까지
 * 소화하는가"라는 상한 측정이다. 그래서 재고를 소진되지 않을 만큼 크게 잡아,
 * 재고 부족 분기 없이 같은 행에 대한 경합만 남긴다.
 *
 * 측정 대상은 POST /orders 하나다. 이 경로는 상품 조회(동기 HTTP) → 주문·Outbox 저장까지이며
 * 재고 행 잠금은 포함하지 않는다. 재고 예약은 product-service가 Kafka로 비동기 처리하므로,
 * 단일 행 FOR UPDATE의 상한은 이 스크립트가 아니라 예약 소화율(drain rate)로 따로 잰다.
 * measure-contention.sh가 두 가지를 순서대로 수행한다.
 *
 * 실행:
 *   k6 run -e VUS=100 -e DURATION=20s -e JWT_TOKEN=$JWT_TOKEN inventory-contention.js
 */

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const created = new Counter('orders_created');
const rejected = new Counter('orders_rejected');
const serverErrors = new Counter('server_errors');
const createDuration = new Trend('order_create_ms');

const VUS = parseInt(__ENV.VUS || '50');
const DURATION = __ENV.DURATION || '20s';

export const options = {
  scenarios: {
    contention: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
      gracefulStop: '10s',
    },
  },
  // 상한을 찾는 것이 목적이므로 임계값으로 실행을 중단시키지 않는다.
  thresholds: {},
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8083';
const PRODUCT_ID = parseInt(__ENV.PRODUCT_ID || '1');
const JWT_TOKEN = __ENV.JWT_TOKEN;

const payload = JSON.stringify({ productId: PRODUCT_ID, quantity: 1 });
const params = {
  headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${JWT_TOKEN}` },
  timeout: '30s',
};

export default function () {
  const start = Date.now();
  const res = http.post(`${BASE_URL}/api/v1/orders`, payload, params);
  createDuration.add(Date.now() - start);

  check(res, { '5xx 없음': (r) => r.status < 500 });

  if (res.status === 200 || res.status === 201) {
    created.add(1);
  } else if (res.status >= 500) {
    serverErrors.add(1);
  } else {
    rejected.add(1);
  }
}
