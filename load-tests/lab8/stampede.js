// ЛР8: одночасний удар N VUs по одному «холодному» дорогому ключу (top-products: fan-out агрегація по шардах).
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE } from '../lib/common.js';

const cacheStatus = new Counter('cache_status');

export const options = {
  scenarios: {
    burst: { executor: 'per-vu-iterations', vus: Number(__ENV.VUS || 500), iterations: 1, maxDuration: '60s' },
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    'cache_status{status:HIT}': ['count>=0'],
    'cache_status{status:MISS}': ['count>=0'],
    'cache_status{status:COALESCED}': ['count>=0'],
  },
};

export default function () {
  const res = http.get(`${BASE}/api/stats/top-products?limit=10`, { timeout: '30s' });
  cacheStatus.add(1, { status: res.headers['X-Cache'] || 'none' });
  check(res, { 'status 200': (r) => r.status === 200 });
}
