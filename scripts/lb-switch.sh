#!/usr/bin/env bash
# Live-перемикання алгоритму балансування без перезапуску nginx: scripts/lb-switch.sh least-conn
set -euo pipefail
ALGO=${1:?round-robin|least-conn|weighted|ip-hash}
ENGINE=${ENGINE:-podman}
LB=${LB:-shop_lb_1}
$ENGINE exec "$LB" sh -c "sed -i -E 's#upstream-[a-z0-9-]+\.conf#upstream-$ALGO.conf#' /etc/nginx/conf.d/default.conf && nginx -t -q && nginx -s reload"
echo "nginx: $($ENGINE exec "$LB" grep -o "upstream-[a-z0-9-]*.conf" /etc/nginx/conf.d/default.conf)"
