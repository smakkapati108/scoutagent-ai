"""Walk-forward cross-validation, model benchmarking, calibration, and a parity check against the Java model."""
from __future__ import annotations

import json
import urllib.request
from typing import Callable

import numpy as np
import pandas as pd
from sklearn.base import ClassifierMixin
from sklearn.calibration import calibration_curve
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, brier_score_loss, log_loss
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

from .features import ELO_HOME_ADV, FEATURES

# The backend's gradient descent minimizes mean log loss + (l2 / 2) * ||w||^2 with l2 = 1e-3.
# scikit-learn minimizes C * sum(log loss) + ||w||^2 / 2, which is equivalent when C = 1 / (l2 * n_train).
JAVA_L2 = 1e-3


def logistic_regression(n_train: int) -> ClassifierMixin:
    return make_pipeline(StandardScaler(), LogisticRegression(C=1.0 / (JAVA_L2 * n_train), max_iter=5000))


def gradient_boosting(_: int) -> ClassifierMixin:
    # Shallow trees and a slow learning rate: single games are noisy, so the model must not chase variance.
    return HistGradientBoostingClassifier(max_depth=3, learning_rate=0.05, max_iter=300, min_samples_leaf=80,
                                          l2_regularization=1.0, early_stopping=False, random_state=0)


MODELS: dict[str, Callable[[int], ClassifierMixin]] = {
    "logistic_regression": logistic_regression,
    "gradient_boosting": gradient_boosting,
}


def baseline_probabilities(df: pd.DataFrame) -> dict[str, np.ndarray]:
    """Rule-based baselines expressed as hard 0/1 picks (accuracy only)."""
    return {
        "always_home": np.ones(len(df)),
        "better_record": (df["home_win_pct_before"] >= df["away_win_pct_before"]).astype(float).to_numpy(),
        "elo_only": (df["elo_diff"] * 100 + ELO_HOME_ADV >= 0).astype(float).to_numpy(),
    }


def scores(y: np.ndarray, p: np.ndarray) -> dict[str, float]:
    return {
        "accuracy": float(accuracy_score(y, p >= 0.5)),
        "log_loss": float(log_loss(y, np.clip(p, 1e-9, 1 - 1e-9), labels=[0, 1])),
        "brier": float(brier_score_loss(y, p)),
    }


def expected_calibration_error(y: np.ndarray, p: np.ndarray, bins: int = 10) -> float:
    """Game-weighted mean |predicted - observed| over equal-width probability bins."""
    idx = np.minimum((p * bins).astype(int), bins - 1)
    ece = 0.0
    for b in range(bins):
        mask = idx == b
        if mask.any():
            ece += mask.mean() * abs(p[mask].mean() - y[mask].mean())
    return float(ece)


def walk_forward(features: pd.DataFrame, first_test_season: int, warmup_seasons: int = 1) -> dict:
    """
    Expanding-window, time-ordered cross-validation: for each test season S, fit on every earlier season
    (after the warm-up season, whose features are cold) and score on season S only. The model never trains on
    a game played after the games it is scored on.
    """
    seasons = sorted(features["season"].unique())
    usable = features[features["season"] > seasons[warmup_seasons - 1]]
    folds, oof = [], []
    for test_season in [s for s in seasons if s >= first_test_season]:
        train = usable[usable["season"] < test_season]
        test = usable[usable["season"] == test_season]
        x_tr, y_tr = train[FEATURES].to_numpy(), train["home_won"].to_numpy()
        x_te, y_te = test[FEATURES].to_numpy(), test["home_won"].to_numpy()

        fold = {"test_season": int(test_season), "train_seasons": f"{int(train.season.min())}-{int(train.season.max())}",
                "train_games": int(len(train)), "test_games": int(len(test)), "models": {}, "baselines": {}}
        preds = {"season": test["season"].to_numpy(), "y": y_te}
        for name, factory in MODELS.items():
            model = factory(len(train)).fit(x_tr, y_tr)
            p = model.predict_proba(x_te)[:, 1]
            fold["models"][name] = scores(y_te, p)
            preds[name] = p
        for name, picks in baseline_probabilities(test).items():
            fold["baselines"][name] = {"accuracy": float(accuracy_score(y_te, picks))}
        folds.append(fold)
        oof.append(pd.DataFrame(preds))

    oof_df = pd.concat(oof, ignore_index=True)
    summary = {}
    for name in MODELS:
        per_fold = pd.DataFrame([f["models"][name] for f in folds])
        summary[name] = {
            **{f"{k}_mean": float(per_fold[k].mean()) for k in per_fold},
            **{f"{k}_std": float(per_fold[k].std(ddof=1)) for k in per_fold},
            "pooled": scores(oof_df["y"].to_numpy(), oof_df[name].to_numpy()),
            "pooled_ece": expected_calibration_error(oof_df["y"].to_numpy(), oof_df[name].to_numpy()),
        }
    for name in folds[0]["baselines"]:
        accs = [f["baselines"][name]["accuracy"] for f in folds]
        summary[name] = {"accuracy_mean": float(np.mean(accs)), "accuracy_std": float(np.std(accs, ddof=1))}

    home_acc = summary["always_home"]["accuracy_mean"]
    for name in MODELS:
        summary[name]["relative_lift_vs_home_pct"] = 100 * (summary[name]["accuracy_mean"] - home_acc) / home_acc

    calibration = {}
    for name in MODELS:
        observed, predicted = calibration_curve(oof_df["y"], oof_df[name], n_bins=10, strategy="quantile")
        calibration[name] = {"predicted": predicted.tolist(), "observed": observed.tolist()}

    return {"folds": folds, "summary": summary, "calibration": calibration,
            "n_folds": len(folds), "oof_games": int(len(oof_df))}


def fixed_split_parity(features: pd.DataFrame, last_train_season: int, java_api: str | None) -> dict:
    """
    Refits the backend's exact setup (same split, features and regularization) in scikit-learn and compares
    it with the Java model's live metrics, which checks both the feature port and the Java optimizer.
    """
    first = features["season"].min()
    train = features[(features["season"] > first) & (features["season"] <= last_train_season)]
    test = features[features["season"] > last_train_season]
    pipe = logistic_regression(len(train)).fit(train[FEATURES].to_numpy(), train["home_won"].to_numpy())
    p = pipe.predict_proba(test[FEATURES].to_numpy())[:, 1]
    lr = pipe[-1]
    sk = {
        "train_games": int(len(train)), "test_games": int(len(test)),
        **scores(test["home_won"].to_numpy(), p),
        "coefficients": {"intercept": float(lr.intercept_[0]), **dict(zip(FEATURES, map(float, lr.coef_[0])))},
    }
    result = {"sklearn": sk}
    if java_api:
        try:
            with urllib.request.urlopen(f"{java_api}/api/model/metrics", timeout=5) as resp:
                java = json.load(resp)
            java_coefs = {c["feature"]: c["weight"] for c in java["coefficients"]}
            result["java"] = {"train_games": java["train_games"], "test_games": java["test_games"],
                              "accuracy": java["test"]["accuracy"], "log_loss": java["test"]["log_loss"],
                              "brier": java["test"]["brier_score"], "coefficients": java_coefs}
            result["max_abs_coefficient_diff"] = max(abs(java_coefs[k] - sk["coefficients"][k]) for k in java_coefs)
            result["accuracy_diff"] = abs(java["test"]["accuracy"] - sk["accuracy"])
        except OSError as e:
            result["java_error"] = f"Java API unavailable ({e}); skipped the comparison."
    return result
