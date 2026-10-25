#!/usr/bin/env python3
"""Графіки та таблиці ЛР8 (results/lab08 → docs/img/lab08-*.png, markdown у stdout)."""
import csv
import json
import os
import statistics
import sys

sys.path.insert(0, os.path.dirname(__file__))
from plots import bar_chart, line_chart, plt, save  # noqa: E402

R, IMG = "results/lab08", "docs/img"
MODES = ["NONE", "SINGLE_FLIGHT", "LOCK", "LOCK_SINGLE_FLIGHT"]

rows = list(csv.DictReader(open(f"{R}/stampede.csv")))
print("#### Cache Stampede: 500 одночасних запитів, холодний ключ top-products (по 3 прогони)\n")
print("| Режим | Перерахунків у БД (scatter-запитів на шард) | Throughput (RPS) | p50, мс | p95, мс | p99, мс | max, мс | Error Rate | MISS / COALESCED / HIT |")
print("|---|---|---|---|---|---|---|---|---|")
agg = {}
for mode in MODES:
    rs = [r for r in rows if r["mode"] == mode]
    med = lambda k: statistics.median(float(r[k]) for r in rs)
    agg[mode] = {k: med(k) for k in ("shard_queries", "rps", "p95", "p99", "errors")}
    loads = " / ".join(r["shard_queries"] for r in rs)
    print(f"| {mode} | {loads} | {med('rps'):.0f} | {med('p50'):.0f} | {med('p95'):.0f} | {med('p99'):.0f} | {med('max'):.0f} | "
          f"{' / '.join(r['errors'] for r in rs)} % | {' ; '.join(r['miss'] + '/' + r['coalesced'] + '/' + r['hit'] for r in rs)} |")
print()
fig, (a1, a2) = plt.subplots(1, 2, figsize=(10, 3.8))
labels = ["NONE", "SINGLE\nFLIGHT", "LOCK", "LOCK +\nSF"]
bar_chart(a1, labels, [("Перерахунків у БД (медіана)", [agg[m]["shard_queries"] for m in MODES])], "запитів",
          "Звернення до СУБД на 500 MISS")
bar_chart(a2, labels, [("p99, мс (медіана)", [agg[m]["p99"] for m in MODES])], "мс", "p99 латентність")
save(fig, f"{IMG}/lab08-stampede.png")

series = []
for name in ("avalanche-sync", "avalanche-jitter"):
    data = list(csv.DictReader(open(f"{R}/{name}-timeline.csv")))
    series.append([int(r["db_calls"]) for r in data])
t = list(range(1, len(series[0]) + 1))
print("#### Cache Avalanche: 3000 ключів, TTL 20 с, постійний потік 1500 запитів/с\n")
print("| Режим | Пік SQL/с | Секунд з > 300 SQL/с | Сумарно запитів у БД за 60 с | p95, мс | p99, мс | max, мс |")
print("|---|---|---|---|---|---|---|")
for label, name, s in (("Без jitter (синхронний TTL)", "avalanche-sync", series[0]), ("TTL jitter ±20 %", "avalanche-jitter", series[1])):
    d = json.load(open(f"{R}/{name}.json"))["metrics"]["http_req_duration"]
    print(f"| {label} | {max(s)} | {sum(1 for v in s if v > 300)} | {sum(s)} | {d['p(95)']:.1f} | {d['p(99)']:.1f} | {d['max']:.1f} |")
print()
fig, ax = plt.subplots(figsize=(9, 3.8))
line_chart(ax, t, [("Синхронний TTL", series[0]), ("TTL jitter ±20 %", series[1])], "SQL-запитів/с на Replica",
           "Cache Avalanche: звернення до БД у часі", label_last=False, marker="", lw=1.8)
ax.set_xlabel("Час від початку потоку, с (усі ключі записано за ~2 с до t=0, TTL = 20 с)")
save(fig, f"{IMG}/lab08-avalanche.png")

print("#### Cache Penetration: неіснуючі id, 50 VUs, 30 с\n")
print("| Патерн | Захист | Запитів у БД | Throughput (RPS) | p95, мс | p99, мс |")
print("|---|---|---|---|---|---|")
pen = {}
for pattern, plabel in (("repeat", "1000 однакових неіснуючих id"), ("unique", "щоразу новий випадковий id")):
    for mode, mlabel in (("none", "без захисту"), ("negative", "Negative Caching"), ("bloom", "Bloom Filter")):
        n = f"pen-{pattern}-{mode}"
        m = json.load(open(f"{R}/{n}.json"))["metrics"]
        q = int(open(f"{R}/{n}.db").read().split()[-1])
        pen[(pattern, mode)] = q
        print(f"| {plabel} | {mlabel} | {q:,} | {m['http_reqs']['rate']:.0f} | {m['http_req_duration']['p(95)']:.1f} | "
              f"{m['http_req_duration']['p(99)']:.1f} |".replace(",", " "))
fig, ax = plt.subplots(figsize=(8, 3.8))
bar_chart(ax, ["Повтор 1000 id", "Унікальні id"],
          [(lbl, [pen[(p, m)] for p in ("repeat", "unique")]) for m, lbl in
           (("none", "без захисту"), ("negative", "Negative Caching"), ("bloom", "Bloom Filter"))],
          "запитів у БД за 30 с", "Cache Penetration")
save(fig, f"{IMG}/lab08-penetration.png")
