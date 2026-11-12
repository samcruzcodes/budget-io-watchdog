import pandas as pd

from metrics import best_threshold, score


def labeled(*rows):
    """Rows of (issue_type, scenario_id, edge_case)."""
    frame = pd.DataFrame(rows, columns=["issue_type", "scenario_id", "edge_case"])
    frame["is_issue"] = frame["issue_type"] != "NONE"
    return frame


# One card-testing run of three charges, one duplicate, a double coffee, and two ordinary purchases.
FRAME = labeled(
    ("CARD_TESTING", "attack", ""), ("CARD_TESTING", "attack", ""), ("CARD_TESTING", "attack", ""),
    ("DUPLICATE_CHARGE", "pair", ""),
    ("NONE", "coffee", "DOUBLE_COFFEE"),
    ("NONE", "", ""), ("NONE", "", ""),
)


def test_one_alert_on_an_incident_catches_it():
    # Like a rule: flag only the last charge of the attack, miss the duplicate, and flag the double coffee.
    result = score(FRAME, [False, False, True, False, True, False, False])

    assert result["confusion"] == {"tp": 1, "fp": 1, "fn": 3, "tn": 2}
    assert result["transaction"]["recall"] == 1 / 4         # three of the four labeled charges weren't flagged
    assert result["incident"] == {"precision": 0.5, "recall": 0.5, "f1": 0.5, "incidents": 2, "caught": 1,
                                  "false_alarms": 1}
    assert result["recall_by_issue_type"]["CARD_TESTING"] == {"incidents": 1, "caught": 1, "recall": 1.0}
    assert result["recall_by_issue_type"]["DUPLICATE_CHARGE"]["recall"] == 0.0


def test_flagging_every_charge_of_one_incident_does_not_buy_extra_precision():
    # Like a model: all three charges of the attack flagged, plus the double coffee. One problem caught, one false
    # alarm. Counted per transaction that would be 3 right out of 4; counted per incident it is 1 out of 2.
    result = score(FRAME, [True, True, True, False, True, False, False])

    assert result["transaction"]["precision"] == 3 / 4
    assert result["incident"]["precision"] == 1 / 2
    assert result["incident"]["caught"] == 1


def test_false_positives_are_counted_by_what_they_landed_on():
    result = score(FRAME, [False, False, False, False, True, True, False])

    assert result["false_positives_by_kind"] == {
        "DOUBLE_COFFEE": {"transactions": 1, "flagged": 1},
        "ORDINARY": {"transactions": 2, "flagged": 1},
    }


def test_nothing_flagged_gives_no_precision_rather_than_zero():
    result = score(FRAME, [False] * 7)

    assert result["transaction"]["precision"] is None
    assert result["incident"]["recall"] == 0.0
    assert result["incident"]["f1"] is None


def test_the_threshold_with_the_best_incident_f1_is_chosen():
    scores = [0.2, 0.3, 0.9, 0.8, 0.6, 0.1, 0.1]  # both incidents score above the coffee

    threshold = best_threshold(FRAME, scores, candidates=[0.05, 0.5, 0.7, 0.95])

    assert threshold == 0.7  # catches both incidents with no false positive
