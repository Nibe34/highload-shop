#!/usr/bin/env python3
"""ЛР10: Baseline (код ЛР5) vs Final — таблиці та графіки (results/lab10/{baseline,final} → docs/img/lab10-*.png)."""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from plots import line_chart, plt, save  # noqa: E402

R = "results/lab10"
LEVELS = [10, 25, 50, 100, 200]
SLO_P95 = 250.0      # мс — поріг «стабільної» роботи
SLO_ERR = 1.0        # %
NAMES = {"A": "A — Read-Intensive", "B": "B — Write-Intensive", "C": "C — Complex Workflow"}


def load(cfg, s, v):
    p = f"{R}/{cfg}/{s}-{v}"
    m = json.load(open(p + ".json"))["metrics"]
    d = m["http_req_duration"]
    cpu = {k: float(x) for k, x in (l.split() for l in open(p + ".cpu"))}
    return {"rps": m["http_reqs"]["rate"], "p50": d["med"], "p95": d["p(95)"], "p99": d["p(99)"],
            "err": m["http_req_failed"]["value"] * 100, "cpu": cpu}


def summary(cfg, s):
    rows = {v: load(cfg, s, v) for v in LEVELS}
    ok = [v for v in LEVELS if rows[v]["p95"] <= SLO_P95 and rows[v]["err"] < SLO_ERR]
    stable = max((rows[v]["rps"] for v in ok), default=0)
    top = max(r["rps"] for r in rows.values())
    sat = next(v for v in LEVELS if rows[v]["rps"] >= 0.9 * top)
    return rows, stable, sat


def pct(a, b):
    return f"{(b - a) / a * 100:+.0f} %" if a else "—"


for s in "ABC":
    rb, stable_b, sat_b = summary("baseline", s)
    rf, stable_f, sat_f = summary("final", s)
    print(f"#### {NAMES[s]}\n")
    print("| Метрика продуктивності | Початковий стан (Lab 5 Baseline) | Фінальний стан (Final Architecture) | Приріст / Оптимізація |")
    print("|---|---|---|---|")
    print(f"| Stable Throughput (RPS; p95 ≤ {SLO_P95:.0f} мс; помилки < {SLO_ERR:.0f} %) | {stable_b:,.0f} | {stable_f:,.0f} | {pct(stable_b, stable_f)} |".replace(",", " "))
    for key, name in (("p50", "p50 Latency @ 100 VUs, мс"), ("p95", "p95 Latency @ 100 VUs, мс"), ("p99", "p99 Tail Latency @ 100 VUs, мс")):
        print(f"| {name} | {rb[100][key]:.1f} | {rf[100][key]:.1f} | {pct(rb[100][key], rf[100][key])} |")
    print(f"| Error Rate at Saturation (200 VUs) | {rb[200]['err']:.2f} % | {rf[200]['err']:.2f} % | — |")
    print(f"| Saturation Threshold (VUs, ≥ 90 % від max RPS) | {sat_b} | {sat_f} | — |")
    print()
    print("<details><summary>Повна таблиця по ступенях навантаження</summary>\n")
    print("| VUs | Baseline RPS | Final RPS | Baseline p95 / p99, мс | Final p95 / p99, мс | Errors B / F |")
    print("|---|---|---|---|---|---|")
    for v in LEVELS:
        b, f = rb[v], rf[v]
        print(f"| {v} | {b['rps']:.0f} | {f['rps']:.0f} | {b['p95']:.1f} / {b['p99']:.1f} | {f['p95']:.1f} / {f['p99']:.1f} | {b['err']:.2f} / {f['err']:.2f} % |")
    print("\n</details>\n")

fig, axes = plt.subplots(2, 3, figsize=(13, 7))
for i, s in enumerate("ABC"):
    rb, _, _ = summary("baseline", s)
    rf, _, _ = summary("final", s)
    line_chart(axes[0][i], LEVELS, [("Baseline (ЛР5)", [rb[v]["rps"] for v in LEVELS]), ("Final", [rf[v]["rps"] for v in LEVELS])],
               "RPS", NAMES[s], label_last=False)
    line_chart(axes[1][i], LEVELS, [("Baseline p99", [rb[v]["p99"] for v in LEVELS]), ("Final p99", [rf[v]["p99"] for v in LEVELS])],
               "p99, мс (log)", logy=True, label_last=False)
    for ax in (axes[0][i], axes[1][i]):
        ax.set_xscale("log")
        ax.set_xticks(LEVELS, [str(v) for v in LEVELS])
    axes[1][i].set_xlabel("Virtual Users")
save(fig, "docs/img/lab10-baseline-vs-final.png")

print("#### Утилізація CPU при 100 VUs (ядер)\n")
print("| Сценарій | Конфігурація | app Σ (3 × 1 CPU) | nginx | Redis | Catalog Primary | Replica | Shard A | Shard B |")
print("|---|---|---|---|---|---|---|---|---|")
for s in "ABC":
    for cfg in ("baseline", "final"):
        c = load(cfg, s, 100)["cpu"]
        g = lambda key: next((v for k, v in c.items() if k.endswith(key)), None)
        app = sum(v for k, v in c.items() if "_app-" in k)
        f = lambda v: "—" if v is None else f"{v:.2f}"
        print(f"| {s} | {cfg} | {app:.2f} | {f(g('_lb_1'))} | {f(g('_redis_1'))} | {f(g('_postgres_1'))} | {f(g('postgres-replica_1'))} | "
              f"{f(g('postgres-shard-a_1'))} | {f(g('postgres-shard-b_1'))} |")
