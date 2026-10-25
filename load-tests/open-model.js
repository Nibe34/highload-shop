// Відкрита модель навантаження (constant-arrival-rate): нові запити надходять з фіксованою частотою
// незалежно від того, чи встигає система. Порівняння з closed model показує ефект Coordinated Omission.
import http from 'k6/http';
import { check } from 'k6';
import { BASE, popularProductId } from './lib/common.js';

export const options = {
  scenarios: {
    open: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 1000),
      timeUnit: '1s',
      duration: __ENV.DURATION || '30s',
      preAllocatedVUs: 500,
      maxVUs: 3000,
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const res = http.get(`${BASE}/api/products/${popularProductId()}`, { timeout: '10s' });
  check(res, { 'product 200': (r) => r.status === 200 });
}
