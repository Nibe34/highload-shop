#!/usr/bin/env bash
# ЛР2: ланцюжок запитів через різні інстанси без sticky sessions.
# POST → app-1, GET → app-2, PUT → app-2, GET → app-1 (+ сесія/кошик створені на одному, використані на іншому).
set -euo pipefail
A=${A:-http://localhost:8081}
B=${B:-http://localhost:8082}
J='Content-Type: application/json'

req() { # method url [body] [auth]
  local m=$1 u=$2 d=${3:-} a=${4:-}
  local args=(-s -X "$m" -D /tmp/lab2.h -o /tmp/lab2.b)
  [[ -n $d ]] && args+=(-H "$J" -d "$d")
  [[ -n $a ]] && args+=(-H "Authorization: Bearer $a")
  curl "${args[@]}" "$u"
  printf '%-6s %-45s -> %s %s\n' "$m" "${u#http://}" "$(grep -i '^x-instance-id' /tmp/lab2.h | tr -d '\r' | cut -d' ' -f2)" "$(head -1 /tmp/lab2.h | cut -d' ' -f2)"
  printf '       %s\n' "$(head -c 160 /tmp/lab2.b)"
}

echo "### 1. CRUD товару через різні інстанси"
req POST "$A/api/products" '{"categoryId":1,"name":"Cross v1","description":"","priceCents":1000,"stock":5}'
PID=$(sed -E 's/.*"id":([0-9]+).*/\1/' /tmp/lab2.b)
req GET "$B/api/products/$PID"
req PUT "$B/api/products/$PID" '{"categoryId":1,"name":"Cross v2","description":"","priceCents":1500,"stock":5}'
req GET "$A/api/products/$PID"

echo; echo "### 2. Сесія та кошик: логін на app-1, кошик на app-2, checkout на app-1"
EMAIL="cross$(date +%s%N)@shop.test"
req POST "$A/api/users" "{\"email\":\"$EMAIL\",\"fullName\":\"Cross\",\"region\":\"Kyiv\",\"password\":\"secret123\"}"
req POST "$A/api/auth/login" "{\"email\":\"$EMAIL\",\"password\":\"secret123\"}"
TOKEN=$(sed -E 's/.*"token":"([^"]+)".*/\1/' /tmp/lab2.b)
req POST "$B/api/cart/items" "{\"productId\":$PID,\"quantity\":2}" "$TOKEN"
req POST "$A/api/cart/items" '{"productId":2,"quantity":1}' "$TOKEN"
req GET  "$B/api/cart" "" "$TOKEN"
req POST "$A/api/orders" "" "$TOKEN"
req GET  "$B/api/orders/my" "" "$TOKEN"
req GET  "$B/api/cart" "" "$TOKEN"
req GET  "$A/api/stats/summary"
req GET  "$B/api/stats/summary"
