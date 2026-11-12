# Rules compared with machine learning (Phase 6)

An offline experiment: can a supervised model, given the same history the six rules see, tell the simulator's planted problems from legitimate transactions better than the rules do? Nothing here runs in the live pipeline, and the Java rules are unchanged.

```
analysis/generate_dataset.sh                      # a few minutes; needs Docker. Writes data/seed-101.csv and seed-202.csv
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/python experiment.py                    # about a minute. Writes results/metrics.json and the dashboard's copy
.venv/bin/python -m pytest tests                  # 16 tests
```

| File | What it does |
|---|---|
| `generate_dataset.sh`, `compose.ml.yml` | run the pipeline for a seed and export what it stored |
| `features.py` | features from each card's past only |
| `metrics.py` | one scoring function for rules and models |
| `experiment.py` | split, train, pick thresholds on validation, score the test run once, save everything |
| `results/metrics.json` | the saved results (committed; the datasets, about 44 MB each, are not) |

Everything is seeded: the same two seeds and `RANDOM_STATE` give the same numbers.

## How the data was made

`analysis/generate_dataset.sh` runs the real pipeline, once per seed, in its own Compose project: the simulator publishes one simulated year for 300 people (all five personas) to Kafka at full speed, the watchdog stores every transaction and label and runs the six rules, and the script exports one CSV per seed from Postgres. So the rules' side of the comparison is the alerts the Java rule engine really raised, not a re-implementation. Demo scenarios (`source = DEMO`) and income are left out.

The preset (`config/simulator.ml.env`) plants problems less often than the demo but still far more often than real life, so there's enough of each kind to learn from.

| Split | Source | Transactions | Cards | Issue transactions | Share | Incidents |
|---|---|---|---|---|---|---|
| Train | seed 101, 75% of cards | 196,723 | 225 | 5,365 | 2.7% | 2,160 |
| Validation | seed 101, the other 25% | 61,892 | 75 | 1,819 | 2.9% | 707 |
| Test | seed 202, a separate run | 258,705 | 300 | 7,136 | 2.8% | 2,903 |

- **Train and validation** share a simulator run but never a card. **Test** is a different run with a different seed: different people, habits, and incidents. It was scored once, after the models and thresholds were fixed.
- About 3% of transactions belong to an incident, so accuracy is useless here (never flagging anything would be 97% accurate) and isn't reported.

## What the label means

A transaction is "suspicious" if the simulator's answer key gives it an issue type. That's one transaction for a duplicate, a price hike, a trial conversion, or a foreign charge, but about five for a card-testing run (every probe and the final purchase) and for a binge day (every purchase in the category that day). The rules alert once per incident by design, so two scores are reported:

- **Precision:** of the transactions flagged, the share that are labeled.
- **Incident recall:** of the planted incidents, the share with at least one flagged labeled transaction. This is how the watchdog has always scored its rules.
- **Transaction recall** is reported too, with the warning that it understates the rules (35% against 85% by incident).

## Features, and how leakage was kept out

`features.py` walks each card's transactions in time order and writes a row's features from the state built by *earlier* transactions only; the state is updated after. 20 numbers and the category: the amount, how it compares with the card's average, whether the country is new for the card, days since a travel purchase, small charges and all charges in the last hour and day, seconds since an identical charge, how often and how recently the card used this merchant, the last amount there and the gap before it, and today's category total against the card's usual day.

Never used: the issue type, the look-alike type, the scenario ID, the persona, the rules' alerts, user and card IDs, and the merchant's name and country code themselves (the simulator's fraud uses its own fictional merchants, so a model could memorize names).

Tests check that changing later transactions doesn't change an earlier row's features, that one card's history never reaches another card, and that no card is in both train and validation.

## Models

Logistic regression (median imputation with a "missing" flag, a log squeeze, scaling, balanced class weights) and a random forest (300 trees, at least 3 rows per leaf, balanced class weights). No hyperparameter search. Each model's threshold is the one with the best incident-level F1 on the validation cards.

## Results on the held-out run

| Approach | Precision | Incident recall | F1 | Transaction recall | PR-AUC | Legitimate transactions flagged |
|---|---|---|---|---|---|---|
| Six rules (Java) | 42% | 85% | 56% | 35% | n/a | 3,450 |
| Logistic regression | 63% | 84% | 72% | 69% | 0.6334 | 2,873 |
| Random forest | 93% | 97% | 95% | 79% | 0.904 | 402 |
| Random forest, fewer simulator tells | 92% | 96% | 94% | 78% | 0.8919 | 462 |

Incidents caught, by kind:

| Planted problem | Rules | Logistic regression | Random forest |
|---|---|---|---|
| CARD_TESTING | 100% | 100% | 100% |
| DUPLICATE_CHARGE | 100% | 85% | 93% |
| NEW_COUNTRY | 89% | 91% | 95% |
| PRICE_HIKE | 86% | 64% | 98% |
| SPENDING_SPIKE | 59% | 84% | 95% |
| TRIAL_CONVERSION | 100% | 86% | 100% |

Legitimate transactions flagged, by kind (the look-alikes the simulator plants so nothing scores perfectly):

| Legitimate behavior | Transactions | Rules flagged | Logistic regression | Random forest |
|---|---|---|---|---|
| APP_STORE_PURCHASES | 1,561 | 650 | 194 | 2 |
| CANCELLED_TRIAL | 737 | 0 | 40 | 0 |
| DOUBLE_COFFEE | 1,208 | 604 | 604 | 32 |
| FOREIGN_ONLINE_ORDER | 371 | 296 | 35 | 3 |
| LEGIT_BIG_PURCHASE | 759 | 470 | 572 | 0 |
| LEGIT_TRAVEL | 8,321 | 147 | 75 | 31 |
| ORDINARY | 236,792 | 913 | 1,339 | 334 |
| TOLL_ROAD_TRIP | 1,820 | 370 | 14 | 0 |

Where the rules and the forest disagree (2,903 incidents, 251,569 legitimate transactions):

- **Only the forest caught:** 391 incidents, mostly spending spikes (281) and price hikes (67). The spike rule needs three standard deviations over a fixed baseline, and the hike rule only fires after two steady months, so a second hike in a row gets past it.
- **Only the rules caught:** 49 incidents (24 duplicates, 16 foreign charges, 9 spikes). A rule's condition is exact; the forest's score for these fell under its threshold.
- **Both missed:** 48 incidents, mostly mild binge days.
- **Only the rules flagged, wrongly:** 3,330 legitimate transactions, most of them planted look-alikes (app purchases, second coffees, big planned purchases, tolls, foreign online orders).
- **Only the forest flagged, wrongly:** 282, nearly all ordinary shopping that happened to be large for the card.
- **Both flagged, wrongly:** 120.

## What this means

On this data the random forest is clearly better than the rules, and logistic regression is better on F1 but worse on duplicates, price hikes, and trial conversions, where the signal is an exact condition a straight line can't express. The rules' recall is already 85%; what they lack is precision, because a fixed condition can't tell a duplicate from a second coffee.

What that does *not* show:

- **It's the simulator, not the world.** The models were trained on output of the same generator they were tested on. A duplicate always posts 5 to 90 seconds later and a second coffee 1 to 5 minutes later; big purchases come in a few fixed prices. A forest learns those lines exactly. Removing the three features most likely to give the simulator away (`merchant_prior_tx`, `days_since_merchant`, `hour`) only moved F1 from 95% to 94%, so it isn't leaning on those, but the look-alikes themselves are tidier than real life.
- **Labels are perfect and instant here.** Real labels come from disputes, weeks later, and only for some transactions.
- **Nobody is adapting.** Real fraud changes to avoid whatever detects it.
- **The rules weren't tuned.** Their thresholds are starting values; each model's threshold was fitted to labeled data.
- **The features were written knowing the six problems.** A kind of problem nobody planted has no labels to learn from, while a rule for it can be written the same day. And a rule explains itself in a sentence; a forest gives a score.

Would adding a model to the live pipeline be worth it? The evidence supports trying it as a *second opinion*, not a replacement: keep the rules for their explanations and for new problems, and use a model's score to suppress the look-alike false positives and to catch spikes the fixed threshold misses. It would also need what this experiment didn't: the same features computed in the stream, a way to collect real labels, and retraining. These numbers are from simulated data and are not a claim about real-world fraud detection.
