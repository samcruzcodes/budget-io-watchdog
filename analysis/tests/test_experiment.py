import json
from pathlib import Path

import pandas as pd

from experiment import split_by_card

ROOT = Path(__file__).parent.parent


def test_no_card_is_in_both_train_and_validation_and_the_split_repeats():
    frame = pd.DataFrame({"card_id": [f"card-{n:04d}" for n in range(1, 41) for _ in range(5)]})

    in_train, in_validation = split_by_card(frame)
    again = split_by_card(frame)

    assert not set(frame["card_id"][in_train]) & set(frame["card_id"][in_validation])
    assert frame["card_id"][in_validation].nunique() == 10  # a quarter of 40 cards
    assert (in_train == again[0]).all()


def test_the_dashboard_shows_the_saved_results_and_nothing_else():
    saved = json.loads((ROOT / "results" / "metrics.json").read_text())
    shown = json.loads((ROOT.parent / "dashboard" / "src" / "ml-results.json").read_text())

    assert shown == saved


def test_the_saved_results_keep_the_test_set_separate():
    saved = json.loads((ROOT / "results" / "metrics.json").read_text())

    assert saved["seeds"]["train_and_validation"] != saved["seeds"]["test"]
    for model in saved["models"].values():
        confusion = model["test"]["confusion"]
        assert sum(confusion.values()) == saved["datasets"]["test"]["transactions"]
