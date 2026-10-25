// Спільні утиліти для k6-сценаріїв.
import http from 'k6/http';
import { Counter } from 'k6/metrics';

export const BASE = __ENV.BASE_URL || 'http://localhost:8080';
export const JSON_HEADERS = { 'Content-Type': 'application/json' };

// Кількість відповідей від кожного інстансу (тег instance = X-Instance-ID).
export const byInstance = new Counter('responses_by_instance');

export function track(res) {
  byInstance.add(1, { instance: res.headers['X-Instance-Id'] || 'none' });
  return res;
}

// Псевдо-Ципф: невеликі id популярніші (як у seed-даних).
export function popularProductId(max = 10000) {
  return 1 + Math.floor(Math.pow(Math.random(), 3) * (max - 1));
}

export function uniformProductId(max = 10000) {
  return 1 + Math.floor(Math.random() * max);
}

// Логін пулу seed-користувачів у setup(): повертає масив токенів.
export function loginUsers(count, offset = 1) {
  const reqs = [];
  for (let i = 0; i < count; i++) {
    reqs.push(['POST', `${BASE}/api/auth/login`,
      JSON.stringify({ email: `user${offset + i}@shop.test`, password: 'password' }),
      { headers: JSON_HEADERS }]);
  }
  const tokens = [];
  for (let i = 0; i < reqs.length; i += 50) {
    for (const r of http.batch(reqs.slice(i, i + 50))) {
      if (r.status === 200) tokens.push(r.json('token'));
    }
  }
  return tokens;
}

export function auth(token) {
  return { headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}` } };
}
