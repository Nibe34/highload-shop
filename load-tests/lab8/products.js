// ЛР8: картки товарів.
//   MODE=warm         — один прохід по id 1..KEYS (одночасне заповнення кешу → однаковий момент вигасання)
//   MODE=steady       — постійний потік (open model, RATE/с) по id 1..KEYS — для спостереження Avalanche
//   MODE=penetration  — запити неіснуючих id: PATTERN=repeat (1000 однакових) або unique (щоразу новий)
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { BASE } from '../lib/common.js';

const MODE = __ENV.MODE || 'steady';
const KEYS = Number(__ENV.KEYS || 3000);

const scenarios = {
  warm: { executor: 'shared-iterations', vus: 100, iterations: KEYS, maxDuration: '60s' },
  steady: { executor: 'constant-arrival-rate', rate: Number(__ENV.RATE || 1500), timeUnit: '1s',
            duration: __ENV.DURATION || '60s', preAllocatedVUs: 200, maxVUs: 1000 },
  penetration: { executor: 'constant-vus', vus: Number(__ENV.VUS || 50), duration: __ENV.DURATION || '30s' },
};

export const options = {
  scenarios: { main: scenarios[MODE] },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  let id;
  if (MODE === 'warm') id = (exec.scenario.iterationInTest % KEYS) + 1;
  else if (MODE === 'steady') id = 1 + Math.floor(Math.random() * KEYS);
  else if (__ENV.PATTERN === 'unique') id = 10_000_000 + Math.floor(Math.random() * 1_000_000_000);
  else id = 10_000_001 + Math.floor(Math.random() * 1000);
  const res = http.get(`${BASE}/api/products/${id}`);
  check(res, { 'expected status': (r) => (MODE === 'penetration' ? r.status === 404 : r.status === 200) });
}
