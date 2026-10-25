// ЛР3: scale-out експеримент (1 / 2 / 3 інстанси) — змішаний профіль читання каталогу та оформлення замовлень.
import http from 'k6/http';
import { check } from 'k6';
import { BASE, track, uniformProductId, loginUsers, auth } from '../lib/common.js';

export const options = {
  scenarios: {
    steady: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 100),
      duration: __ENV.DURATION || '40s',
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

export function setup() {
  return { tokens: loginUsers(200, 1) };
}

export default function (data) {
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  if (Math.random() < 0.9) {
    const res = track(http.get(`${BASE}/api/products?categoryId=${1 + Math.floor(Math.random() * 20)}&size=20`));
    check(res, { 'list 200': (r) => r.status === 200 });
  } else {
    const body = JSON.stringify({ items: [{ productId: uniformProductId(), quantity: 1 }] });
    const res = track(http.post(`${BASE}/api/orders`, body, auth(token)));
    check(res, { 'order 201': (r) => r.status === 201 });
  }
}
