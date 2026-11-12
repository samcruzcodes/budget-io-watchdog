"""Scoring that treats the rules and the models the same way.

Two views of the same predictions:

  - Transaction level: every transaction is one example. A flag on a transaction labeled with an issue is a true
    positive. This is the standard view, and the one PR-AUC uses.
  - Incident level: the simulator plants *incidents* (a duplicated pair, a card-testing run of five charges, a binge
    day). An incident is caught if at least one of its labeled transactions is flagged. This is how the watchdog has
    always scored its rules, and it's what matters to a cardholder: one alert per problem is enough.

Rules are built to alert once per incident, so transaction-level recall undercounts them (four of a card-testing
run's five charges are "missed" by design). The headline comparison therefore uses alert precision with incident
recall, and the transaction-level numbers are reported beside it.
"""

import numpy as np
import pandas as pd


def _ratio(numerator, denominator):
    return float(numerator) / float(denominator) if denominator else None


def _f1(precision, recall):
    if precision is None or recall is None or precision + recall == 0:
        return None
    return 2 * precision * recall / (precision + recall)


def score(frame: pd.DataFrame, flagged) -> dict:
    """Scores one set of flags. `frame` needs is_issue, issue_type, scenario_id, and edge_case, one row per transaction."""
    flagged = np.asarray(flagged, dtype=bool)
    truth = frame["is_issue"].to_numpy(dtype=bool)
    tp, fp = int((flagged & truth).sum()), int((flagged & ~truth).sum())
    fn, tn = int((~flagged & truth).sum()), int((~flagged & ~truth).sum())
    precision, recall = _ratio(tp, tp + fp), _ratio(tp, tp + fn)

    issues = frame[truth].assign(flagged=flagged[truth])
    caught = issues.groupby("scenario_id")["flagged"].any()
    incident_type = issues.groupby("scenario_id")["issue_type"].first()
    incident_recall = _ratio(caught.sum(), len(caught))
    by_type = {}
    for issue_type in sorted(incident_type.unique()):
        of_type = caught[incident_type == issue_type]
        by_type[issue_type] = {"incidents": int(len(of_type)), "caught": int(of_type.sum()),
                               "recall": _ratio(of_type.sum(), len(of_type))}

    # Where the false positives land: each planted look-alike, and everything else.
    legit = frame[~truth].assign(flagged=flagged[~truth])
    kind = legit["edge_case"].fillna("").replace("", "ORDINARY")
    false_positives = {name: {"transactions": int(len(group)), "flagged": int(group["flagged"].sum())}
                       for name, group in legit.groupby(kind)}

    return {
        "confusion": {"tp": tp, "fp": fp, "fn": fn, "tn": tn},
        "transaction": {"precision": precision, "recall": recall, "f1": _f1(precision, recall)},
        "incident": {"precision": precision, "recall": incident_recall, "f1": _f1(precision, incident_recall),
                     "incidents": int(len(caught)), "caught": int(caught.sum())},
        "recall_by_issue_type": by_type,
        "false_positives_by_kind": false_positives,
    }


def best_threshold(frame: pd.DataFrame, scores, candidates=None) -> float:
    """The score cut-off with the best incident-level F1 on `frame`. Only ever called with the validation set."""
    scores = np.asarray(scores)
    if candidates is None:
        candidates = np.unique(np.quantile(scores, np.linspace(0.90, 1.0, 400)))
    best, best_f1 = 0.5, -1.0
    for threshold in candidates:
        f1 = score(frame, scores >= threshold)["incident"]["f1"]
        if f1 is not None and f1 > best_f1:
            best, best_f1 = float(threshold), f1
    return best
