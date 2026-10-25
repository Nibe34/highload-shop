#!/usr/bin/env python3
"""Графіки для звітів (PNG у docs/img). Спільний стиль: тонкі лінії, стримана сітка, одна вісь Y на графік.

Палітра — категоріальні слоти (фіксований порядок): blue, orange, aqua, yellow.
"""
import json
import os

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

SERIES = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100"]
TEXT, TEXT2, GRID, SURFACE = "#0b0b0b", "#52514e", "#e4e3df", "#fcfcfb"

plt.rcParams.update({
    "figure.facecolor": SURFACE, "axes.facecolor": SURFACE, "savefig.facecolor": SURFACE,
    "axes.edgecolor": GRID, "axes.labelcolor": TEXT2, "xtick.color": TEXT2, "ytick.color": TEXT2,
    "text.color": TEXT, "axes.grid": True, "grid.color": GRID, "grid.linewidth": 0.8,
    "axes.spines.top": False, "axes.spines.right": False, "font.size": 10,
    "axes.titlesize": 11, "axes.titleweight": "bold", "legend.frameon": False,
    "lines.linewidth": 2, "lines.markersize": 6,
})


def metrics(path):
    with open(path) as f:
        m = json.load(f)["metrics"]
    d = m["http_req_duration"]
    return {"rps": m["http_reqs"]["rate"], "avg": d["avg"], "p50": d["med"], "p95": d["p(95)"],
            "p99": d["p(99)"], "max": d["max"], "err": m.get("http_req_failed", {}).get("value", 0) * 100}


def line_chart(ax, xs, series, ylabel, title=None, logy=False, label_last=True, marker="o", lw=2):
    """series: [(label, ys)] — у фіксованому порядку слотів палітри."""
    for i, (label, ys) in enumerate(series):
        ax.plot(xs, ys, marker=marker, linewidth=lw, color=SERIES[i % len(SERIES)], label=label)
        if label_last and len(series) <= 4:
            ax.annotate(label, (xs[-1], ys[-1]), xytext=(6, 0), textcoords="offset points",
                        va="center", color=TEXT2, fontsize=9)
    if logy:
        ax.set_yscale("log")
    ax.set_ylabel(ylabel)
    if title:
        ax.set_title(title, loc="left")
    if len(series) >= 2:
        ax.legend(loc="upper left")


def bar_chart(ax, groups, series, ylabel, title=None):
    """groups: підписи груп по X; series: [(label, values)] — згруповані стовпці з проміжком."""
    n = len(series)
    width = 0.8 / n
    for i, (label, vals) in enumerate(series):
        xs = [g + (i - (n - 1) / 2) * width for g in range(len(groups))]
        bars = ax.bar(xs, vals, width=width * 0.92, color=SERIES[i % len(SERIES)], label=label)
        for b, v in zip(bars, vals):
            ax.annotate(f"{v:,.0f}".replace(",", " "), (b.get_x() + b.get_width() / 2, b.get_height()),
                        xytext=(0, 3), textcoords="offset points", ha="center", fontsize=8, color=TEXT2)
    ax.set_xticks(range(len(groups)), groups)
    ax.set_ylabel(ylabel)
    ax.grid(axis="x", visible=False)
    if title:
        ax.set_title(title, loc="left")
    if n >= 2:
        ax.legend(loc="upper left")


def save(fig, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    fig.tight_layout()
    fig.savefig(path, dpi=130)
    plt.close(fig)
    print("saved", path)
