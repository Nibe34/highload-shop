// ЛР7: вартість Shard-Local vs Cross-Shard (fan-out) запитів.
//   MODE=local  — GET /api/orders/my (усі дані користувача на одному шарді); запити тегуються шардом користувача
//   MODE=fanout — GET /api/stats/top-products/live (scatter-gather агрегація по всіх шардах, без кешу)
//   MODE=sales  — GET /api/stats/sales?days=30 (fan-out count/sum по всіх шардах)
import http from 'k6/http';
import { check } from 'k6';
import { BASE, loginUsers, auth } from '../lib/common.js';

const MODE = __ENV.MODE || 'local';
const USERS = Number(__ENV.USERS || 500);

export const options = {
  scenarios: { main: { executor: 'constant-vus', vus: Number(__ENV.VUS || 20), duration: __ENV.DURATION || '30s' } },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  setupTimeout: '180s',
  // пороги — лише щоб k6 вивів латентність окремо для кожного шарду
  thresholds: {
    'http_req_duration{shard:a}': ['max>=0'],
    'http_req_duration{shard:b}': ['max>=0'],
  },
};

export function setup() {
  if (MODE !== 'local') return { tokens: [], shards: [] };
  const tokens = loginUsers(USERS, 1);
  const shards = [];
  for (let i = 0; i < USERS; i++) {
    shards.push(http.get(`${BASE}/api/admin/shards/route?userId=${i + 1}`).json('shard'));
  }
  return { tokens, shards };
}

export default function (data) {
  let res;
  if (MODE === 'local') {
    const i = (__VU * 7 + __ITER) % data.tokens.length;
    res = http.get(`${BASE}/api/orders/my?limit=10`, { ...auth(data.tokens[i]), tags: { shard: data.shards[i] } });
  } else if (MODE === 'fanout') {
    res = http.get(`${BASE}/api/stats/top-products/live?limit=10`);
  } else {
    res = http.get(`${BASE}/api/stats/sales?days=30`);
  }
  check(res, { 'status 200': (r) => r.status === 200 });
}
