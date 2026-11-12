#!/usr/bin/env bash
# Generates the Phase 6 datasets by running the real pipeline: simulator -> Kafka -> watchdog (which stores every
# transaction and label and runs the six rules) -> Postgres, then exports one CSV per seed.
#
#   analysis/generate_dataset.sh              # seeds 101 (train and validation) and 202 (test)
#   analysis/generate_dataset.sh 303          # any other seed
#
# Each seed gets a fresh stack under its own Compose project, so it can't touch the demo stack or another seed's
# data. Needs Docker; takes a few minutes per seed. Output: analysis/data/seed-<seed>.csv (not committed).
set -euo pipefail
cd "$(dirname "$0")/.."

SEEDS=("$@")
[ ${#SEEDS[@]} -eq 0 ] && SEEDS=(101 202)
compose() { docker compose -p watchdog-ml -f docker-compose.yml -f analysis/compose.ml.yml "$@"; }
sql() { compose exec -T postgres psql -U watchdog -d watchdog -At -c "$1"; }
mkdir -p analysis/data

for seed in "${SEEDS[@]}"; do
  echo "== seed $seed: starting a fresh stack"
  export ML_SEED="$seed"
  compose down --volumes --remove-orphans >/dev/null 2>&1 || true
  compose up -d --build kafka postgres watchdog simulator >/dev/null

  echo "== seed $seed: waiting for the simulator to publish one simulated year"
  until compose logs simulator 2>/dev/null | grep -q "Finished: "; do
    if [ "$(compose ps -a --format '{{.State}}' simulator)" = "restarting" ]; then compose logs --tail 20 simulator; exit 1; fi
    sleep 5
  done
  published=$(compose logs simulator | grep "Finished: " | tail -1 | sed -E 's/.*Finished: ([0-9]+) transactions.*/\1/')

  echo "== seed $seed: waiting for the watchdog to store all $published transactions and labels"
  until [ "$(sql 'select count(*) from transactions')" = "$published" ] \
     && [ "$(sql 'select count(*) from ground_truth_labels')" = "$published" ]; do
    sleep 5
  done

  # One row per transaction: what the watchdog stored, the simulator's answer key, and which rules alerted on it.
  # Demo scenarios are left out (there are none in a fresh stack; the filter documents the intent).
  compose exec -T postgres psql -U watchdog -d watchdog -c "\copy (
      SELECT t.transaction_id, t.user_id, t.card_id, t.merchant, t.category, t.amount_cents, t.country,
             t.occurred_at, t.processed_at, l.persona, l.issue_type, l.scenario_id, l.edge_case,
             coalesce((SELECT string_agg(a.detector, '|' ORDER BY a.detector) FROM alerts a
                       WHERE a.transaction_id = t.transaction_id), '') AS rule_alerts
      FROM transactions t JOIN ground_truth_labels l ON l.transaction_id = t.transaction_id
      WHERE t.source <> 'DEMO'
      ORDER BY t.card_id, t.processed_at, t.transaction_id
    ) TO STDOUT WITH CSV HEADER" > "analysis/data/seed-$seed.csv"
  echo "== seed $seed: $(($(wc -l < "analysis/data/seed-$seed.csv") - 1)) rows, $(sql 'select count(*) from alerts') alerts -> analysis/data/seed-$seed.csv"
  compose down --volumes >/dev/null 2>&1
done
