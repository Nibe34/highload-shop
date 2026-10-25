#!/usr/bin/env python3
"""ЛР10: зведення сценаріїв відмов (вікна до / під час / після) + графік часових рядів."""
import csv
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from plots import line_chart, plt, save  # noqa: E402

R = "results/lab10/failures"
NAMES = {"app": "kill app-2", "redis": "stop Redis", "replica": "stop Replica", "shard": "stop Shard B"}


def series(f):
    rows = list(csv.DictReader(open(f"{R}/fail-{f}-timeseries.csv")))
    return rows[next(i for i, r in enumerate(rows) if int(r["vus"]) > 0):]


def window(rows, a, b):
    w = rows[a:b]
    req = sum(int(r["rps"]) for r in w)
    err = sum(int(r["errors"]) for r in w)
    return req / len(w), (err / req * 100 if req else 0), max(float(r["p99"]) for r in w)


print("| Відмова (t = 20…40 с) | До: RPS / err / max p99 | Під час: RPS / err / max p99 | Після (t = 45…58 с): RPS / err / max p99 |")
print("|---|---|---|---|")
for f in NAMES:
    rows = series(f)
    cells = [window(rows, a, b) for a, b in ((3, 18), (21, 38), (45, 58))]
    print(f"| {NAMES[f]} | " + " | ".join(f"{r:.0f} / {e:.1f} % / {p:.0f} мс" for r, e, p in cells) + " |")

fig, axes = plt.subplots(2, 1, figsize=(10, 6.5), sharex=True)
data = {f: series(f) for f in NAMES}
t = list(range(min(len(v) for v in data.values())))
line_chart(axes[0], t, [(NAMES[f], [int(r["rps"]) for r in data[f][:len(t)]]) for f in NAMES], "успішні + неуспішні RPS",
           "Сценарій C, 50 VUs: відмова на t = 20 с, відновлення на t = 40 с", label_last=False, marker="", lw=1.6)
line_chart(axes[1], t, [(NAMES[f], [100 * int(r["errors"]) / max(1, int(r["rps"])) for r in data[f][:len(t)]]) for f in NAMES],
           "помилки, %", label_last=False, marker="", lw=1.6)
for ax in axes:
    ax.axvspan(20, 40, color="#e4e3df", alpha=0.6, lw=0)
axes[1].set_xlabel("Час, с")
save(fig, "docs/img/lab10-failures.png")
