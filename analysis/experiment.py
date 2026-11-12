"""Phase 6: do supervised models catch the planted problems better than the six hand-written rules?

    python experiment.py            # reads data/seed-101.csv and data/seed-202.csv, writes results/metrics.json

Splits:
  - seed 101's cards are divided 75/25 into train and validation. A card is never in both, so no card's history
    is shared between them.
  - seed 202, a separate simulator run with different people, is the test set. It is scored once, at the end, with
    models and thresholds that were fixed using train and validation only.

The rules' flags come from the alerts the real Java rule engine raised on these same transactions as they went
through Kafka, so the baseline is the production code, not a re-implementation.
"""

import json
import shutil
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import RandomForestClassifier
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import average_precision_score
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import FunctionTransformer, OneHotEncoder, StandardScaler

from features import CATEGORICAL_FEATURES, FEATURES, NUMERIC_FEATURES, build_features
from metrics import best_threshold, score

HERE = Path(__file__).parent
TRAIN_SEED, TEST_SEED = 101, 202
RANDOM_STATE = 7
VALIDATION_SHARE = 0.25
EXAMPLES_PER_GROUP = 5

# Features a model could use to recognize the simulator instead of the behavior. The simulator's fraud always comes
# from a merchant the card has never used (real fraud often does too, but not always), and its scenarios happen at
# hours drawn from fixed ranges. The ablation retrains the forest without them to see how much it leans on them.
SIMULATOR_TELLS = ["merchant_prior_tx", "days_since_merchant", "hour"]


def load(seed: int) -> pd.DataFrame:
    """One simulator run, without income (no rule looks at it) and without demo scenarios."""
    frame = pd.read_csv(HERE / "data" / f"seed-{seed}.csv", keep_default_na=False)
    frame = frame[frame["category"] != "INCOME"].reset_index(drop=True)
    frame["is_issue"] = frame["issue_type"] != "NONE"
    frame["rule_flag"] = frame["rule_alerts"] != ""
    return frame


def split_by_card(frame: pd.DataFrame):
    """Train and validation masks with whole cards on one side or the other."""
    cards = np.sort(frame["card_id"].unique())
    rng = np.random.default_rng(RANDOM_STATE)
    validation_cards = set(rng.choice(cards, size=int(len(cards) * VALIDATION_SHARE), replace=False))
    in_validation = frame["card_id"].isin(validation_cards).to_numpy()
    return ~in_validation, in_validation


def signed_log(values):
    """Squeezes long-tailed amounts and counts so one $5,000 purchase doesn't dominate a linear model."""
    return np.sign(values) * np.log1p(np.abs(values))


def logistic_regression(numeric_features=NUMERIC_FEATURES) -> Pipeline:
    numeric = Pipeline([
        ("impute", SimpleImputer(strategy="median", add_indicator=True)),  # "no history yet" becomes its own column
        ("log", FunctionTransformer(signed_log, feature_names_out="one-to-one")),
        ("scale", StandardScaler()),
    ])
    columns = ColumnTransformer([
        ("numeric", numeric, numeric_features),
        ("category", OneHotEncoder(handle_unknown="ignore"), CATEGORICAL_FEATURES),
    ])
    # class_weight="balanced": an issue is rare, so each one counts for as much as the many ordinary rows together.
    return Pipeline([("columns", columns),
                     ("model", LogisticRegression(class_weight="balanced", max_iter=3000, random_state=RANDOM_STATE))])


def random_forest(numeric_features=NUMERIC_FEATURES) -> Pipeline:
    columns = ColumnTransformer([
        ("numeric", SimpleImputer(strategy="median", add_indicator=True), numeric_features),
        ("category", OneHotEncoder(handle_unknown="ignore"), CATEGORICAL_FEATURES),
    ])
    return Pipeline([("columns", columns),
                     ("model", RandomForestClassifier(n_estimators=300, min_samples_leaf=3, n_jobs=-1,
                                                      class_weight="balanced_subsample",
                                                      random_state=RANDOM_STATE))])


def describe(frame: pd.DataFrame) -> dict:
    issues = frame[frame["is_issue"]]
    look_alikes = frame[(~frame["is_issue"]) & (frame["edge_case"] != "")]
    return {
        "transactions": int(len(frame)),
        "cards": int(frame["card_id"].nunique()),
        "issue_transactions": int(len(issues)),
        "legitimate_transactions": int(len(frame) - len(issues)),
        "issue_share": float(len(issues) / len(frame)),
        "incidents": int(issues["scenario_id"].nunique()),
        "incidents_by_type": {k: int(v) for k, v in issues.groupby("issue_type")["scenario_id"].nunique().items()},
        "issue_transactions_by_type": {k: int(v) for k, v in issues["issue_type"].value_counts().sort_index().items()},
        "look_alike_transactions": {k: int(v) for k, v in look_alikes["edge_case"].value_counts().sort_index().items()},
    }


def label_alignment(frame: pd.DataFrame) -> dict:
    """How the answer key's labeled transactions line up with where the rules put their alerts."""
    out = {}
    for issue_type, issues in frame[frame["is_issue"]].groupby("issue_type"):
        incidents = issues["scenario_id"].nunique()
        out[issue_type] = {
            "incidents": int(incidents),
            "labeled_transactions": int(len(issues)),
            "labeled_per_incident": float(len(issues) / incidents),
            "rule_alerts_on_labeled": int(issues["rule_flag"].sum()),
        }
    return out


def example(row, model_score) -> dict:
    return {
        "occurredAt": pd.Timestamp(row["occurred_at"]).isoformat(), "merchant": row["merchant"], "category": row["category"],
        "amountCents": int(row["amount_cents"]), "country": row["country"],
        "truth": row["issue_type"] if row["is_issue"] else (row["edge_case"] or "ORDINARY"),
        "ruleAlerts": row["rule_alerts"], "modelScore": round(float(model_score), 3),
    }


def disagreements(frame: pd.DataFrame, model_flag, model_scores) -> dict:
    """Where the rules and the model part ways on the test set, by incident for issues and by transaction for the rest."""
    frame = frame.assign(model_flag=model_flag, model_score=model_scores)
    issues = frame[frame["is_issue"]]
    incidents = issues.groupby("scenario_id").agg(issue_type=("issue_type", "first"), rules=("rule_flag", "any"),
                                                  model=("model_flag", "any"))
    legit = frame[~frame["is_issue"]].copy()
    legit["kind"] = legit["edge_case"].replace("", "ORDINARY")

    def incident_group(mask):
        chosen = incidents[mask]
        rows = issues[issues["scenario_id"].isin(chosen.index)].sort_values("occurred_at")
        # One example per incident: its last labeled transaction, where a rule would normally alert.
        last = rows.groupby("scenario_id").tail(1).head(EXAMPLES_PER_GROUP)
        return {"count": int(len(chosen)), "by_kind": {k: int(v) for k, v in chosen["issue_type"].value_counts().items()},
                "examples": [example(row, row["model_score"]) for _, row in last.iterrows()]}

    def legit_group(mask):
        chosen = legit[mask]
        top = chosen.sort_values("model_score", ascending=False).head(EXAMPLES_PER_GROUP)
        return {"count": int(len(chosen)), "by_kind": {k: int(v) for k, v in chosen["kind"].value_counts().items()},
                "examples": [example(row, row["model_score"]) for _, row in top.iterrows()]}

    return {
        "rules_right_model_wrong": {
            "incidents_only_rules_caught": incident_group(incidents["rules"] & ~incidents["model"]),
            "legit_only_model_flagged": legit_group(legit["model_flag"] & ~legit["rule_flag"]),
        },
        "model_right_rules_wrong": {
            "incidents_only_model_caught": incident_group(~incidents["rules"] & incidents["model"]),
            "legit_only_rules_flagged": legit_group(legit["rule_flag"] & ~legit["model_flag"]),
        },
        "both_missed": incident_group(~incidents["rules"] & ~incidents["model"]),
        "both_flagged_legit": legit_group(legit["rule_flag"] & legit["model_flag"]),
    }


def rounded(value):
    if isinstance(value, dict):
        return {k: rounded(v) for k, v in value.items()}
    if isinstance(value, list):
        return [rounded(v) for v in value]
    if isinstance(value, float):
        return round(value, 4)
    return value


def main():
    development, test = load(TRAIN_SEED), load(TEST_SEED)
    in_train, in_validation = split_by_card(development)
    train, validation = development[in_train], development[in_validation]
    assert not set(train["card_id"]) & set(validation["card_id"]), "a card is in both train and validation"

    # Features are built per run, over every card's full history, before any split: each row only sees its own past.
    development_x, test_x = build_features(development), build_features(test)
    train_x, validation_x = development_x[in_train], development_x[in_validation]

    results = {
        "about": "Offline experiment on simulated data. The rules' flags are the alerts the Java rule engine raised "
                 "on these transactions; the models were trained in Python afterwards. Nothing here runs in the "
                 "live pipeline.",
        "seeds": {"train_and_validation": TRAIN_SEED, "test": TEST_SEED},
        "features": FEATURES,
        "datasets": {"train": describe(train), "validation": describe(validation), "test": describe(test)},
        "label_alignment_test": label_alignment(test),
        "models": {"rules": {"name": "Six rules (Java)", "validation": score(validation, validation["rule_flag"]),
                             "test": score(test, test["rule_flag"])}},
    }

    fitted = {}
    without_tells = [f for f in NUMERIC_FEATURES if f not in SIMULATOR_TELLS]
    for key, name, build in [("logistic_regression", "Logistic regression", logistic_regression),
                             ("random_forest", "Random forest", random_forest),
                             ("random_forest_ablation", "Random forest, fewer simulator tells",
                              lambda: random_forest(without_tells))]:
        model = build().fit(train_x, train["is_issue"])
        validation_scores = model.predict_proba(validation_x)[:, 1]
        threshold = best_threshold(validation, validation_scores)  # chosen on validation, then frozen
        fitted[key] = (model, threshold)
        results["models"][key] = {
            "name": name, "threshold": threshold,
            "pr_auc_validation": float(average_precision_score(validation["is_issue"], validation_scores)),
            "validation": score(validation, validation_scores >= threshold),
        }

    # Only now does the test set get scored, once, with everything above fixed.
    test_scores = {}
    for key, (model, threshold) in fitted.items():
        test_scores[key] = model.predict_proba(test_x)[:, 1]
        results["models"][key]["pr_auc_test"] = float(average_precision_score(test["is_issue"], test_scores[key]))
        results["models"][key]["test"] = score(test, test_scores[key] >= threshold)

    # Disagreements are shown for whichever model did better on validation (not on test).
    results["ablation_removed_features"] = SIMULATOR_TELLS
    chosen = max(["logistic_regression", "random_forest"], key=lambda k: results["models"][k]["validation"]["incident"]["f1"] or 0)
    results["disagreements"] = {"model": chosen, **disagreements(test, test_scores[chosen] >= fitted[chosen][1],
                                                                 test_scores[chosen])}

    forest = fitted["random_forest"][0]
    names = [n.split("__", 1)[1] for n in forest.named_steps["columns"].get_feature_names_out()]
    importance = sorted(zip(names, forest.named_steps["model"].feature_importances_), key=lambda p: -p[1])[:10]
    results["random_forest_top_features"] = [{"feature": n, "importance": float(v)} for n, v in importance]
    logistic = fitted["logistic_regression"][0]
    names = [n.split("__", 1)[1] for n in logistic.named_steps["columns"].get_feature_names_out()]
    weights = sorted(zip(names, logistic.named_steps["model"].coef_[0]), key=lambda p: -abs(p[1]))[:10]
    results["logistic_regression_top_weights"] = [{"feature": n, "weight": float(v)} for n, v in weights]

    out = HERE / "results" / "metrics.json"
    out.write_text(json.dumps(rounded(results), indent=2) + "\n")
    # The dashboard shows these numbers; it reads its own copy at build time, so the two can't drift apart silently.
    shutil.copyfile(out, HERE.parent / "dashboard" / "src" / "ml-results.json")

    print(f"wrote {out}")
    for key, entry in results["models"].items():
        t = entry["test"]
        print(f"{entry['name']:<22} test: precision {t['incident']['precision']:.3f}  incident recall "
              f"{t['incident']['recall']:.3f}  F1 {t['incident']['f1']:.3f}  | transaction recall "
              f"{t['transaction']['recall']:.3f} F1 {t['transaction']['f1']:.3f}"
              + (f"  PR-AUC {entry['pr_auc_test']:.3f}" if "pr_auc_test" in entry else ""))


if __name__ == "__main__":
    sys.exit(main())
