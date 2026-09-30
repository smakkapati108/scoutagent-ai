"""
Runs the win-probability research pipeline end to end:
  1. load games from PostgreSQL (pandas),
  2. rebuild the backend's leakage-free pre-game features,
  3. walk-forward cross-validate logistic regression and gradient boosting (scikit-learn) against baselines,
  4. check parity with the Java model served by the backend,
and writes results/results.json, results/report.md and results/walk_forward.png.

Usage: python run_experiments.py [--db-url URL] [--java-api http://localhost:8080] [--first-test-season 2019]
"""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

from scoutml.data import load_games
from scoutml.evaluate import MODELS, fixed_split_parity, walk_forward
from scoutml.features import build_features
from scoutml.plots import LABELS, walk_forward_figure

RESULTS = Path(__file__).parent / "results"


def pct(v: float) -> str:
    return f"{v * 100:.1f}%"


def report_markdown(wf: dict, parity: dict, n_games: int) -> str:
    s = wf["summary"]
    lines = [
        "# Win-probability model: walk-forward evaluation",
        "",
        f"{n_games:,} games; {wf['n_folds']} expanding-window folds (train on all earlier seasons, test on the next); "
        f"{wf['oof_games']:,} out-of-fold predictions.",
        "",
        "## Summary (mean ± std across folds)",
        "",
        "| Model | Accuracy | Log loss | Brier | ECE (pooled) |",
        "|---|---|---|---|---|",
    ]
    for name in MODELS:
        m = s[name]
        lines.append(f"| {LABELS[name]} | {pct(m['accuracy_mean'])} ± {m['accuracy_std'] * 100:.1f} | "
                     f"{m['log_loss_mean']:.3f} ± {m['log_loss_std']:.3f} | {m['brier_mean']:.3f} ± {m['brier_std']:.3f} | "
                     f"{m['pooled_ece']:.3f} |")
    baseline_names = {"elo_only": "Elo only", "better_record": "Better record", "always_home": "Always home team"}
    for name, label in baseline_names.items():
        lines.append(f"| {label} (baseline) | "
                     f"{pct(s[name]['accuracy_mean'])} ± {s[name]['accuracy_std'] * 100:.1f} | – | – | – |")
    lines += [
        "",
        f"Logistic regression lift over the always-home baseline: "
        f"{s['logistic_regression']['relative_lift_vs_home_pct']:.1f}% (relative).",
        "",
        "## Per fold",
        "",
        "| Test season | Train seasons | Train games | LR acc | GB acc | Elo acc | Home acc |",
        "|---|---|---|---|---|---|---|",
    ]
    for f in wf["folds"]:
        lines.append(f"| {f['test_season']} | {f['train_seasons']} | {f['train_games']:,} | "
                     f"{pct(f['models']['logistic_regression']['accuracy'])} | {pct(f['models']['gradient_boosting']['accuracy'])} | "
                     f"{pct(f['baselines']['elo_only']['accuracy'])} | {pct(f['baselines']['always_home']['accuracy'])} |")
    lines += ["", "## Parity with the Java model (same split and regularization)", ""]
    sk = parity["sklearn"]
    if "java" in parity:
        j = parity["java"]
        lines += [
            "| | scikit-learn | Java backend |",
            "|---|---|---|",
            f"| Test accuracy | {pct(sk['accuracy'])} | {pct(j['accuracy'])} |",
            f"| Test log loss | {sk['log_loss']:.4f} | {j['log_loss']:.4f} |",
            f"| Test Brier | {sk['brier']:.4f} | {j['brier']:.4f} |",
            "",
            f"Largest coefficient difference: {parity['max_abs_coefficient_diff']:.4f}.",
        ]
    else:
        lines.append(f"scikit-learn test accuracy {pct(sk['accuracy'])}. {parity.get('java_error', '')}")
    lines += ["", "![Walk-forward results](walk_forward.png)", ""]
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--db-url", default=None, help="SQLAlchemy URL (default: local docker-compose Postgres)")
    parser.add_argument("--java-api", default="http://localhost:8080", help="Backend URL for the parity check; '' to skip")
    parser.add_argument("--first-test-season", type=int, default=2019)
    parser.add_argument("--last-train-season", type=int, default=2022, help="Must match scout.model.last-train-season")
    args = parser.parse_args()

    t0 = time.time()
    games = load_games(args.db_url)
    features = build_features(games)
    print(f"Loaded {len(games):,} games and built {len(features.columns)} columns in {time.time() - t0:.1f}s")

    wf = walk_forward(features, first_test_season=args.first_test_season)
    parity = fixed_split_parity(features, args.last_train_season, args.java_api or None)

    RESULTS.mkdir(exist_ok=True)
    (RESULTS / "results.json").write_text(json.dumps({"walk_forward": wf, "parity": parity}, indent=2))
    (RESULTS / "report.md").write_text(report_markdown(wf, parity, len(games)))
    walk_forward_figure(wf, str(RESULTS / "walk_forward.png"))
    print((RESULTS / "report.md").read_text())


if __name__ == "__main__":
    main()
