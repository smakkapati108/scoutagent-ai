"""Figures for the experiment report (light surface; palette validated for color-vision deficiency)."""
from __future__ import annotations

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

SURFACE = "#fcfcfb"
TEXT, TEXT_MUTED, GRID, NEUTRAL = "#0b0b0b", "#52514e", "#e8e7e2", "#b9b8b1"
SERIES = {"logistic_regression": "#2a78d6", "gradient_boosting": "#eb6834", "elo_only": "#1baf7a"}
LABELS = {"logistic_regression": "Logistic regression", "gradient_boosting": "Gradient boosting",
          "elo_only": "Elo-only baseline", "always_home": "Always home"}


def _style(ax):
    ax.set_facecolor(SURFACE)
    ax.grid(True, color=GRID, linewidth=0.8)
    ax.set_axisbelow(True)
    for spine in ax.spines.values():
        spine.set_visible(False)
    ax.tick_params(colors=TEXT_MUTED, length=0, labelsize=9)


def walk_forward_figure(results: dict, path: str) -> None:
    folds = results["folds"]
    seasons = [f["test_season"] for f in folds]
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(11, 4.2), facecolor=SURFACE)

    _style(ax1)
    for name in ("logistic_regression", "gradient_boosting"):
        ys = [f["models"][name]["accuracy"] * 100 for f in folds]
        ax1.plot(seasons, ys, color=SERIES[name], linewidth=2, marker="o", markersize=6,
                 markeredgecolor=SURFACE, markeredgewidth=2, label=LABELS[name])
    ys = [f["baselines"]["elo_only"]["accuracy"] * 100 for f in folds]
    ax1.plot(seasons, ys, color=SERIES["elo_only"], linewidth=2, marker="o", markersize=6,
             markeredgecolor=SURFACE, markeredgewidth=2, label=LABELS["elo_only"])
    ys = [f["baselines"]["always_home"]["accuracy"] * 100 for f in folds]
    ax1.plot(seasons, ys, color=NEUTRAL, linewidth=2, label=LABELS["always_home"])
    ax1.set_title("Walk-forward accuracy by test season", color=TEXT, fontsize=11, loc="left")
    ax1.set_ylabel("Accuracy (%)", color=TEXT_MUTED, fontsize=9)
    ax1.set_xticks(seasons)
    ax1.legend(frameon=False, fontsize=8, labelcolor=TEXT_MUTED, loc="lower right")

    _style(ax2)
    ax2.plot([0, 1], [0, 1], color=NEUTRAL, linewidth=1)
    for name, cal in results["calibration"].items():
        ax2.plot(cal["predicted"], cal["observed"], color=SERIES[name], linewidth=2, marker="o", markersize=6,
                 markeredgecolor=SURFACE, markeredgewidth=2, label=LABELS[name])
    ax2.set_title("Calibration (pooled out-of-fold, decile bins)", color=TEXT, fontsize=11, loc="left")
    ax2.set_xlabel("Predicted home-win probability", color=TEXT_MUTED, fontsize=9)
    ax2.set_ylabel("Observed home-win rate", color=TEXT_MUTED, fontsize=9)
    ax2.legend(frameon=False, fontsize=8, labelcolor=TEXT_MUTED, loc="upper left")

    fig.tight_layout()
    fig.savefig(path, dpi=160, facecolor=SURFACE)
    plt.close(fig)
