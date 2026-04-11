/**
 * 스파이크 부하 테스트 — Weverse 컴백/한정판 드롭 시나리오
 *
 * 목적: 특정 시점에 트래픽이 폭발적으로 집중되는 패턴에서
 *       시스템의 응답시간, 에러율, 처리량(RPS)을 측정
 *
 * 시나리오:
 *   - 평상시: 10명 (일반 트래픽)
 *   - 컴백 발표 순간: 10초 안에 1000명으로 급증
 *   - 피크 유지: 30초
 *   - 점진적 감소: 10초
 *
 * 실행:
 *   k6 run -e JWT_TOKEN=$JWT_TOKEN -e BASE_URL=http://localhost:8083 -e PRODUCT_ID=1 spike-load.js
 *
 * Grafana 연동 (실시간 모니터링):
 *   k6 run --out experimental-prometheus-rw \
 *     -e K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write \
 *     -e JWT_TOKEN=$JWT_TOKEN spike-load.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const successOrders = new Counter('success_orders');
const failedOrders = new Counter('failed_orders');
const serverErrors = new Counter('server_errors');
const errorRate = new Rate('error_rate');
const orderDuration = new Trend('order_duration_ms');

export const options = {
  scenarios: {
    // 평상시 → 스파이크 → 감소 패턴
    weverse_comeback: {
      executor: 'ramping-vus',
      startVUs: 10,
      stages: [
        { duration: '10s', target: 10 },    // 평상시 트래픽
        { duration: '10s', target: 1000 },  // 컴백 발표 — 급증
        { duration: '30s', target: 1000 },  // 피크 유지
        { duration: '10s', target: 10 },    // 점진적 감소
        { duration: '10s', target: 0 },     // 종료
      ],
    },
  },
  thresholds: {
    // SLA 기준
    'http_req_duration': [
      'p(50)<500',    // 중간값 0.5초 이내
      'p(95)<2000',   // 95%가 2초 이내
      'p(99)<5000',   // 99%가 5초 이내
    ],
    // 5xx 에러율 1% 미만 (재고 부족 4xx는 정상)
    'error_rate': ['rate<0.01'],
    // 서버 에러 절대 건수
    'server_errors': ['count<50'],
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
    timeout: '15s',
  };

  const start = Date.now();
  const res = http.post(`${BASE_URL}/api/v1/orders`, payload, params);
  const elapsed = Date.now() - start;
  orderDuration.add(elapsed);

  const isSuccess = res.status === 200 || res.status === 201;
  const isClientError = res.status >= 400 && res.status < 500; // 재고 부족 등 정상 실패
  const isServerError = res.status >= 500;

  check(res, {
    '5xx 에러 없음': (r) => r.status < 500,
    '응답시간 2초 이내': () => elapsed < 2000,
  });

  errorRate.add(isServerError ? 1 : 0);

  if (isSuccess) {
    successOrders.add(1);
  } else if (isClientError) {
    failedOrders.add(1);
  } else if (isServerError) {
    serverErrors.add(1);
    console.error(`[5xx] vu=${__VU} iter=${__ITER} status=${res.status} duration=${elapsed}ms`);
  }
}

export function handleSummary(data) {
  const success = data.metrics['success_orders'] ? data.metrics['success_orders'].values.count : 0;
  const failed = data.metrics['failed_orders'] ? data.metrics['failed_orders'].values.count : 0;
  const errors = data.metrics['server_errors'] ? data.metrics['server_errors'].values.count : 0;
  const total = success + failed + errors;

  const p50 = data.metrics['http_req_duration'] ? data.metrics['http_req_duration'].values['p(50)'].toFixed(0) : '-';
  const p95 = data.metrics['http_req_duration'] ? data.metrics['http_req_duration'].values['p(95)'].toFixed(0) : '-';
  const p99 = data.metrics['http_req_duration'] ? data.metrics['http_req_duration'].values['p(99)'].toFixed(0) : '-';
  const rps = data.metrics['http_reqs'] ? data.metrics['http_reqs'].values.rate.toFixed(1) : '-';

  console.log('\n========== 스파이크 부하 테스트 결과 ==========');
  console.log(`총 요청: ${total}건`);
  console.log(`주문 성공: ${success}건`);
  console.log(`재고 부족 실패(4xx): ${failed}건`);
  console.log(`서버 에러(5xx): ${errors}건`);
  console.log('');
  console.log('[ 응답시간 ]');
  console.log(`  p50: ${p50}ms`);
  console.log(`  p95: ${p95}ms  (SLA: 2000ms)`);
  console.log(`  p99: ${p99}ms  (SLA: 5000ms)`);
  console.log('');
  console.log(`[ 처리량 ] ${rps} RPS`);
  console.log('');
  console.log('[ 판정 기준 ]');
  console.log('  ✅ p95 < 2000ms & 5xx < 1% → 시스템 안정');
  console.log('  ❌ p95 >= 2000ms 또는 5xx >= 1% → 병목 존재, 튜닝 필요');
  console.log('==============================================\n');

  return {
    stdout: JSON.stringify(data, null, 2),
  };
}
