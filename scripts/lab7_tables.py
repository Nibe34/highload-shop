#!/usr/bin/env python3
"""Таблиці ЛР7 з results/lab07 (markdown у stdout)."""
import json
import sys

R = sys.argv[1] if len(sys.argv) > 1 else "results/lab07"


def load(name):
    m = json.load(open(f"{R}/{name}.json"))["metrics"]
    cpu = dict(line.split() for line in open(f"{R}/{name}.cpu"))
    return m, {k: float(v) for k, v in cpu.items()}


def dur(m, key="http_req_duration"):
    return m.get(key, {})


def fmt(n):
    return f"{n:,.0f}".replace(",", " ")


def shards(name):
    lines = open(f"{R}/{name}.shards").read().split("\n")
    b = dict(x.split("=") for x in lines[0].split(":", 1)[1].split())
    a = dict(x.split("=") for x in lines[1].split(":", 1)[1].split())
    return {k: int(a[k]) - int(b[k]) for k in b}


print("#### Hot Shard — запис (B, 100 VUs)\n")
print("| Стратегія | RPS | p50, мс | p95, мс | p99, мс | Записів на a / b | Частка a / b | CPU shard a / b |")
print("|---|---|---|---|---|---|---|---|")
for label, n in (("REGION", "hot-region-write"), ("BUCKETS", "hot-buckets-write")):
    m, c = load(n)
    d = dur(m)
    w = shards(n)
    tot = sum(w.values())
    print(f"| {label} | {fmt(m['http_reqs']['rate'])} | {d['med']:.1f} | {d['p(95)']:.1f} | {d['p(99)']:.1f} | "
          f"{fmt(w['a_1'])} / {fmt(w['b_1'])} | {w['a_1'] / tot:.0%} / {w['b_1'] / tot:.0%} | "
          f"{c['shop_postgres-shard-a_1']:.2f} / {c['shop_postgres-shard-b_1']:.2f} |")

print("\n#### Hot Shard — shard-local читання (`GET /orders/my`, 50 VUs), латентність окремо для користувачів кожного шарду\n")
print("| Стратегія | RPS | p95 загальний, мс | Користувачі шарду a: p50 / p95 / p99, мс | Користувачі шарду b: p50 / p95 / p99, мс | CPU shard a / b |")
print("|---|---|---|---|---|---|")
for label, n in (("REGION", "hot-region-read"), ("BUCKETS", "hot-buckets-read")):
    m, c = load(n)
    d, da, db = dur(m), dur(m, "http_req_duration{shard:a}"), dur(m, "http_req_duration{shard:b}")
    print(f"| {label} | {fmt(m['http_reqs']['rate'])} | {d['p(95)']:.1f} | {da['med']:.1f} / {da['p(95)']:.1f} / {da['p(99)']:.1f} | "
          f"{db['med']:.1f} / {db['p(95)']:.1f} / {db['p(99)']:.1f} | "
          f"{c['shop_postgres-shard-a_1']:.2f} / {c['shop_postgres-shard-b_1']:.2f} |")

print("\n#### Shard-Local vs Cross-Shard (20 VUs, 2 шарди)\n")
print("| Запит | Тип | RPS | p50, мс | p95, мс | p99, мс | CPU shard a / b |")
print("|---|---|---|---|---|---|---|")
for label, kind, n in (("`GET /orders/my`", "shard-local", "query-local"),
                       ("`GET /stats/sales?days=30`", "fan-out (count/sum)", "query-sales"),
                       ("`GET /stats/top-products/live`", "fan-out (GROUP BY + merge)", "query-fanout")):
    m, c = load(n)
    d = dur(m)
    print(f"| {label} | {kind} | {fmt(m['http_reqs']['rate'])} | {d['med']:.1f} | {d['p(95)']:.1f} | {d['p(99)']:.1f} | "
          f"{c['shop_postgres-shard-a_1']:.2f} / {c['shop_postgres-shard-b_1']:.2f} |")

print("\n#### Single Node vs Sharded\n")
print("| Навантаження | Single: RPS | Single: p95 / p99, мс | Sharded: RPS | Sharded: p95 / p99, мс | Speedup | Err single / sharded | CPU shards single → sharded | CPU catalog primary (single / sharded) |")
print("|---|---|---|---|---|---|---|---|---|")
rows = [(f"{s} · {v} VUs", f"{s}-{v}") for s in "BC" for v in (50, 100, 200)]
rows += [("query-local · 20 VUs", "query-local"), ("query-fanout · 20 VUs", "query-fanout")]
for label, suffix in rows:
    ms, cs = load(f"single-{suffix}")
    mh, ch = load(f"sharded-{suffix}")
    ds, dh = dur(ms), dur(mh)
    es, eh = ms["http_req_failed"]["value"] * 100, mh["http_req_failed"]["value"] * 100
    print(f"| {label} | {fmt(ms['http_reqs']['rate'])} | {ds['p(95)']:.1f} / {ds['p(99)']:.1f} | {fmt(mh['http_reqs']['rate'])} | "
          f"{dh['p(95)']:.1f} / {dh['p(99)']:.1f} | {mh['http_reqs']['rate'] / ms['http_reqs']['rate']:.2f}× | {es:.2f} / {eh:.2f} % | "
          f"{cs['shop_postgres-shard-a_1']:.2f} → {ch['shop_postgres-shard-a_1']:.2f} + {ch['shop_postgres-shard-b_1']:.2f} | "
          f"{cs['shop_postgres_1']:.2f} / {ch['shop_postgres_1']:.2f} |")
