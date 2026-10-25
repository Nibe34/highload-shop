// ЛР3: розподіл трафіку і латентність при асиметричному навантаженні.
// Запуск: k6 run -e VUS=50 -e DURATION=30s load-tests/lab3/balance.js
import http from 'k6/http';
import { check } from 'k6';
import { BASE, track, uniformProductId } from '../lib/common.js';

export const options = {
  scenarios: {
    steady: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 50),
      duration: __ENV.DURATION || '30s',
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  // пороги лише для того, щоб k6 вивів підметрики по кожному інстансу
  thresholds: {
    'responses_by_instance{instance:app-1}': ['count>=0'],
    'responses_by_instance{instance:app-2}': ['count>=0'],
    'responses_by_instance{instance:app-3}': ['count>=0'],
  },
};

export default function () {
  const res = track(http.get(`${BASE}/api/products/${uniformProductId()}`));
  check(res, { 'status 200': (r) => r.status === 200 });
}
