#!/usr/bin/env bash
# Демонстрація основних endpoints через curl. Використання: scripts/smoke.sh [base_url]
set -euo pipefail
BASE=${1:-http://localhost:8080}
J='Content-Type: application/json'
EMAIL="demo$(date +%s%N)@shop.test"

step() { printf '\n\033[1;34m== %s\033[0m\n' "$*"; }

step "POST /api/users — реєстрація"
curl -s -XPOST "$BASE/api/users" -H "$J" -d "{\"email\":\"$EMAIL\",\"fullName\":\"Demo\",\"region\":\"Lviv\",\"password\":\"secret123\"}"
step "POST /api/auth/login"
TOKEN=$(curl -s -XPOST "$BASE/api/auth/login" -H "$J" -d "{\"email\":\"$EMAIL\",\"password\":\"secret123\"}" | sed -E 's/.*"token":"([^"]+)".*/\1/')
echo "token=$TOKEN"
AUTH="Authorization: Bearer $TOKEN"
step "GET /api/categories"
curl -s "$BASE/api/categories" | head -c 200; echo
step "GET /api/products?categoryId=2&size=3"
curl -s "$BASE/api/products?categoryId=2&size=3" | head -c 300; echo
step "POST /api/products — створення товару"
PID=$(curl -s -XPOST "$BASE/api/products" -H "$J" -d '{"categoryId":1,"name":"Demo product","description":"demo","priceCents":1999,"stock":10}' | sed -E 's/.*"id":([0-9]+).*/\1/')
echo "productId=$PID"
step "PUT /api/products/$PID — оновлення"
curl -s -XPUT "$BASE/api/products/$PID" -H "$J" -d '{"categoryId":1,"name":"Demo product v2","description":"demo","priceCents":2499,"stock":10}'; echo
step "POST /api/cart/items — додати в кошик"
curl -s -XPOST "$BASE/api/cart/items" -H "$AUTH" -H "$J" -d "{\"productId\":$PID,\"quantity\":3}"; echo
step "POST /api/orders — checkout з кошика"
curl -s -XPOST "$BASE/api/orders" -H "$AUTH"; echo
step "POST /api/orders — недостатньо товару (очікується 409)"
curl -s -w ' HTTP %{http_code}' -XPOST "$BASE/api/orders" -H "$AUTH" -H "$J" -d "{\"items\":[{\"productId\":$PID,\"quantity\":100}]}"; echo
step "GET /api/orders/my"
curl -s "$BASE/api/orders/my" -H "$AUTH"; echo
step "GET /api/stats/top-products?limit=3"
curl -s "$BASE/api/stats/top-products?limit=3"; echo
step "DELETE /api/products/$PID (до ЛР7 — 409 через FK; після шардингу FK між БД немає → 204)"
curl -s -w ' HTTP %{http_code}' -XDELETE "$BASE/api/products/$PID"; echo
