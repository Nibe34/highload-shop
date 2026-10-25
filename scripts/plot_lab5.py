#!/usr/bin/env python3
"""Графіки та таблиці ЛР5 з results/lab05/*.json → docs/img/lab05-*.png + markdown у stdout."""
import csv
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from plots import bar_chart, line_chart, metrics, plt, save  # noqa: E402

R = sys.argv[1] if len(sys.argv) > 1 else "results/lab05"
IMG = sys.argv[2] if len(sys.argv) > 2 else "docs/img"
PREFIX = sys.argv[3] if len(sys.argv) > 3 else "lab05"
LEVELS = [10, 25, 50, 100, 200]
NAMES = {"A": "A — Read-Intensive", "B": "B — Write-Intensive", "C": "C — Complex Workflow"}


def have(name):
    return os.path.exists(f"{R}/{name}.json")


def db_rate(name):
    try:
        return int(open(f"{R}/{name}.db").read().split()[3])
    except (OSError, IndexError, ValueError):
        return None


def cpu(name):
    try:
        return {k: float(v) for k, v in (line.split() for line in open(f"{R}/{name}.cpu"))}
    except OSError:
        return {}


def table(rows):
    print("| Навантаження | Throughput (RPS) | Avg, мс | p50, мс | p95, мс | p99, мс | Error Rate | SQL/с | CPU app Σ / PG, ядер |")
    print("|---|---|---|---|---|---|---|---|---|")
    for label, name in rows:
        m = metrics(f"{R}/{name}.json")
        c = cpu(name)
        app = sum(v for k, v in c.items() if "app" in k)
        pg = c.get("shop_postgres_1", 0)
        print(f"| {label} | {m['rps']:.0f} | {m['avg']:.1f} | {m['p50']:.1f} | {m['p95']:.1f} | {m['p99']:.1f} | "
              f"{m['err']:.2f} % | {db_rate(name) or '—'} | {app:.2f} / {pg:.2f} |")
    print()


scen = [s for s in "ABC" if all(have(f"{s}-{v}") for v in LEVELS)]
for s in scen:
    print(f"#### {NAMES[s]}\n")
    table([(f"{v} VUs", f"{s}-{v}") for v in LEVELS])

if scen:
    data = {s: [metrics(f"{R}/{s}-{v}.json") for v in LEVELS] for s in scen}
    fig, ax = plt.subplots(figsize=(7.5, 4))
    line_chart(ax, LEVELS, [(NAMES[s][:1], [m["rps"] for m in data[s]]) for s in scen], "RPS",
               "Throughput залежно від кількості VUs")
    ax.set_xlabel("Virtual Users")
    ax.set_xscale("log")
    ax.set_xticks(LEVELS, [str(v) for v in LEVELS])
    save(fig, f"{IMG}/{PREFIX}-throughput.png")

    fig, axes = plt.subplots(1, len(scen), figsize=(4.2 * len(scen), 3.8), sharey=True)
    for ax, s in zip(axes if len(scen) > 1 else [axes], scen):
        line_chart(ax, LEVELS, [(p, [m[p] for m in data[s]]) for p in ("p50", "p95", "p99")],
                   "Латентність, мс (log)", NAMES[s], logy=True)
        ax.set_xscale("log")
        ax.set_xticks(LEVELS, [str(v) for v in LEVELS])
        ax.set_xlabel("Virtual Users")
    save(fig, f"{IMG}/{PREFIX}-latency.png")

ts = f"{R}/ramp-C-timeseries.csv"
if os.path.exists(ts):
    rows = list(csv.DictReader(open(ts)))
    # перші секунди — логіни з setup(), не навантаження: починаємо з моменту, коли з'явились VUs
    start = next(i for i, r in enumerate(rows) if int(r["vus"]) > 0)
    rows = rows[start:]
    t = [int(r["t"]) - int(rows[0]["t"]) for r in rows]
    fig, (a1, a2, a3) = plt.subplots(3, 1, figsize=(8, 7.5), sharex=True)
    line_chart(a1, t, [("RPS", [int(r["rps"]) for r in rows])], "RPS", "Ramp-профіль сценарію C: 10 → 25 → 50 → 100 → 200 VUs",
               label_last=False, marker="", lw=1.5)
    line_chart(a2, t, [("p50", [float(r["p50"]) for r in rows]), ("p95", [float(r["p95"]) for r in rows]),
                       ("p99", [float(r["p99"]) for r in rows])], "мс (log)", logy=True, label_last=False, marker="", lw=1.5)
    line_chart(a3, t, [("VUs", [int(r["vus"]) for r in rows])], "VUs", label_last=False, marker="", lw=1.5)
    a3.set_xlabel("Час, с")
    save(fig, f"{IMG}/{PREFIX}-ramp-C.png")

if all(have(f"scale1-{s}-{v}") for s in "AC" for v in (50, 100, 200)):
    print("#### Scaling Impact (1 vs 3 instances)\n")
    print("| Сценарій | VUs | RPS, 1 інстанс | RPS, 3 інстанси | Speedup | Scalability Efficiency | p95 1 → 3, мс | p99 1 → 3, мс |")
    print("|---|---|---|---|---|---|---|---|")
    groups, one, three = [], [], []
    for s in "AC":
        for v in (50, 100, 200):
            m1, m3 = metrics(f"{R}/scale1-{s}-{v}.json"), metrics(f"{R}/{s}-{v}.json")
            sp = m3["rps"] / m1["rps"]
            print(f"| {s} | {v} | {m1['rps']:.0f} | {m3['rps']:.0f} | {sp:.2f}× | {sp / 3 * 100:.0f} % | "
                  f"{m1['p95']:.1f} → {m3['p95']:.1f} | {m1['p99']:.1f} → {m3['p99']:.1f} |")
            groups.append(f"{s}·{v}")
            one.append(m1["rps"])
            three.append(m3["rps"])
    print()
    fig, ax = plt.subplots(figsize=(8, 4))
    bar_chart(ax, groups, [("1 інстанс", one), ("3 інстанси", three)], "RPS", "Scaling Impact (сценарій·VUs)")
    save(fig, f"{IMG}/{PREFIX}-scaling.png")

if all(have(f"nocache-A-{v}") for v in LEVELS):
    print("#### Caching Impact (сценарій A)\n")
    print("| VUs | RPS без кешу | RPS з кешем | p95 без / з кешем, мс | p99 без / з кешем, мс | SQL/с без кешу | SQL/с з кешем | CPU PG без / з кешем |")
    print("|---|---|---|---|---|---|---|---|")
    rps_off, rps_on, sql_off, sql_on = [], [], [], []
    for v in LEVELS:
        off, on = metrics(f"{R}/nocache-A-{v}.json"), metrics(f"{R}/A-{v}.json")
        print(f"| {v} | {off['rps']:.0f} | {on['rps']:.0f} | {off['p95']:.1f} / {on['p95']:.1f} | "
              f"{off['p99']:.1f} / {on['p99']:.1f} | {db_rate(f'nocache-A-{v}')} | {db_rate(f'A-{v}')} | "
              f"{cpu(f'nocache-A-{v}').get('shop_postgres_1', 0):.2f} / {cpu(f'A-{v}').get('shop_postgres_1', 0):.2f} |")
        rps_off.append(off["rps"]); rps_on.append(on["rps"])
        sql_off.append(db_rate(f"nocache-A-{v}") or 0); sql_on.append(db_rate(f"A-{v}") or 0)
    print()
    fig, (a1, a2) = plt.subplots(1, 2, figsize=(10, 3.8))
    bar_chart(a1, [str(v) for v in LEVELS], [("Cache Disabled", rps_off), ("Cache Enabled", rps_on)], "RPS",
              "Throughput, сценарій A")
    bar_chart(a2, [str(v) for v in LEVELS], [("Cache Disabled", sql_off), ("Cache Enabled", sql_on)], "SQL-запитів/с",
              "Навантаження на PostgreSQL")
    a1.set_xlabel("VUs"); a2.set_xlabel("VUs")
    save(fig, f"{IMG}/{PREFIX}-caching.png")

for a, b, title in (("cold-A-100", "warm-A-100", "Cold vs Warm cache (A, 100 VUs, 10 с)"),
                    ("co-closed", "co-open", "Coordinated Omission: closed vs open model (1 інстанс)")):
    if have(a) and have(b):
        print(f"#### {title}\n")
        table([(a, a), (b, b)])
