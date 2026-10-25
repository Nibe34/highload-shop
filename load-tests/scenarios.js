// Сценарії навантаження ЛР5 (перевикористовуються в ЛР6–ЛР10).
//   SCENARIO=A — Read-Intensive: картки популярних товарів (80%) + сторінки каталогу (20%)
//   SCENARIO=B — Write-Intensive: оформлення замовлень на 1–3 випадкові товари
//   SCENARIO=C — Complex Workflow: каталог → картка → кошик → checkout → історія замовлень
// Профіль: constant-vus (VUS, DURATION) або ступеневий ramp (PROFILE=ramp).
import http from 'k6/http';
import { check, group } from 'k6';
import { Trend } from 'k6/metrics';
import { BASE, JSON_HEADERS, track, popularProductId, uniformProductId, loginUsers, auth } from './lib/common.js';

const SCENARIO = (__ENV.SCENARIO || 'A').toUpperCase();
const VUS = Number(__ENV.VUS || 10);
const DURATION = __ENV.DURATION || '30s';

const workflowDuration = new Trend('workflow_duration', true);

const constant = { executor: 'constant-vus', vus: VUS, duration: DURATION };
const ramp = {
  executor: 'ramping-vus',
  startVUs: 0,
  stages: [
    { duration: '10s', target: 10 }, { duration: '30s', target: 10 },
    { duration: '10s', target: 25 }, { duration: '30s', target: 25 },
    { duration: '10s', target: 50 }, { duration: '30s', target: 50 },
    { duration: '10s', target: 100 }, { duration: '30s', target: 100 },
    { duration: '10s', target: 200 }, { duration: '30s', target: 200 },
    { duration: '10s', target: 0 },
  ],
};

export const options = {
  scenarios: { main: __ENV.PROFILE === 'ramp' ? ramp : constant },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  setupTimeout: '120s',
};

export function setup() {
  // У B/C кожен VU працює від імені свого користувача (до 1000 seed-користувачів).
  return SCENARIO === 'A' ? { tokens: [] } : { tokens: loginUsers(Number(__ENV.USERS || 1000), 1) };
}

function scenarioA() {
  if (Math.random() < 0.8) {
    const res = track(http.get(`${BASE}/api/products/${popularProductId()}`, { tags: { name: 'GET /products/{id}' } }));
    check(res, { 'product 200': (r) => r.status === 200 });
  } else {
    const cat = 1 + Math.floor(Math.random() * 20);
    const page = Math.floor(Math.pow(Math.random(), 2) * 10);
    const res = track(http.get(`${BASE}/api/products?categoryId=${cat}&page=${page}&size=20`,
      { tags: { name: 'GET /products?categoryId' } }));
    check(res, { 'catalog 200': (r) => r.status === 200 });
  }
}

function scenarioB(token) {
  const n = 1 + Math.floor(Math.random() * 3);
  const items = [];
  const used = new Set();
  while (items.length < n) {
    const id = uniformProductId();
    if (!used.has(id)) { used.add(id); items.push({ productId: id, quantity: 1 }); }
  }
  const res = track(http.post(`${BASE}/api/orders`, JSON.stringify({ items }), { ...auth(token), tags: { name: 'POST /orders' } }));
  check(res, { 'order 201': (r) => r.status === 201 });
}

function scenarioC(token) {
  const start = Date.now();
  const p = auth(token);
  group('workflow', () => {
    const cat = 1 + Math.floor(Math.random() * 20);
    const list = track(http.get(`${BASE}/api/products?categoryId=${cat}&size=20`, { tags: { name: 'GET /products?categoryId' } }));
    check(list, { 'catalog 200': (r) => r.status === 200 });
    const productId = popularProductId();
    track(http.get(`${BASE}/api/products/${productId}`, { tags: { name: 'GET /products/{id}' } }));
    const add = track(http.post(`${BASE}/api/cart/items`, JSON.stringify({ productId, quantity: 1 }), { ...p, tags: { name: 'POST /cart/items' } }));
    check(add, { 'cart 200': (r) => r.status === 200 });
    const order = track(http.post(`${BASE}/api/orders`, null, { ...p, tags: { name: 'POST /orders' } }));
    check(order, { 'order 201': (r) => r.status === 201 });
    const my = track(http.get(`${BASE}/api/orders/my?limit=10`, { ...p, tags: { name: 'GET /orders/my' } }));
    check(my, { 'history 200': (r) => r.status === 200 });
  });
  workflowDuration.add(Date.now() - start);
}

export default function (data) {
  const token = data.tokens.length ? data.tokens[(__VU - 1) % data.tokens.length] : null;
  if (SCENARIO === 'A') scenarioA();
  else if (SCENARIO === 'B') scenarioB(token);
  else scenarioC(token);
}
