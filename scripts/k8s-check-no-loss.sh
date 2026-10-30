#!/usr/bin/env bash
# Proves nothing was lost or stored twice on the kind cluster, by comparing the actual ID sets, not just counts:
#   - every transaction ID on the `transactions` topic is a row in Postgres, and every row came from the topic
#   - every alert row is marked published, and the alert IDs on the `alerts` topic are exactly the alert rows
#   - both dead-letter topics are empty, and both consumer groups are at lag 0
# It shows delivery, not detection: whether the rules raised the same alerts as an undisturbed run is a separate
# question (the cross-pod rule-memory gap, Phase 3.5b).
#
# Scale the simulator to 0 first, so the topics stop changing:
#   .tools/bin/kubectl --context kind-watchdog -n watchdog scale deployment simulator --replicas=0
set -uo pipefail

cd "$(dirname "$0")/.."
k() { .tools/bin/kubectl --context kind-watchdog -n watchdog "$@"; }
# Kafka's tools inside the broker's pod. The pod sets a 512 MB heap for the broker, which `exec` would inherit; the
# tools get a small one so they can't push the pod over its memory limit.
kafka() { k exec kafka-0 -- env KAFKA_HEAP_OPTS=-Xmx256m "$@"; }
psql() { k exec postgres-0 -- psql -U watchdog -d watchdog -At -c "$1"; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
failures=0
check() { # name, condition result (0 = pass), detail
  if [ "$2" -eq 0 ]; then echo "PASS  $1: $3"; else echo "FAIL  $1: $3"; failures=$((failures + 1)); fi
}

# Total lag of a consumer group; waits up to a minute for it to reach 0.
lag() {
  kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group "$1" 2>/dev/null \
    | awk '$6 ~ /^[0-9]+$/ {sum += $6} END {print sum + 0}'
}
for group in watchdog watchdog-labels; do
  for _ in $(seq 1 30); do [ "$(lag "$group")" = 0 ] && break; sleep 2; done
  n=$(lag "$group"); check "lag $group" "$([ "$n" = 0 ]; echo $?)" "$n messages behind"
done

for dlt in transactions.DLT transaction-labels.DLT; do
  n=$(kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic "$dlt" 2>/dev/null \
    | awk -F: '{sum += $3} END {print sum + 0}')
  check "$dlt empty" "$([ "$n" = 0 ]; echo $?)" "$n messages"
done

# Every message on a topic, read from the beginning; the tool stops after 10 s with nothing new.
read_topic() {
  kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic "$1" \
    --from-beginning --timeout-ms 10000 2>/dev/null
}
# Compares the IDs found on a topic with the IDs in a table: both as sets, plus duplicate messages on the topic.
compare() { # name, field in the message, SQL that lists the IDs
  read_topic "$2" > "$work/messages"
  grep -o "\"$3\":\"[0-9a-f-]*\"" "$work/messages" | cut -d'"' -f4 | sort > "$work/topic-all"
  sort -u "$work/topic-all" > "$work/topic"
  psql "$4" | sort > "$work/db"
  local on_topic in_db missing extra duplicates
  on_topic=$(wc -l < "$work/topic" | tr -d ' ')
  in_db=$(wc -l < "$work/db" | tr -d ' ')
  missing=$(comm -23 "$work/topic" "$work/db" | wc -l | tr -d ' ') # on the topic, not stored
  extra=$(comm -13 "$work/topic" "$work/db" | wc -l | tr -d ' ')   # stored, not on the topic
  duplicates=$(( $(wc -l < "$work/topic-all") - on_topic ))
  check "$1 sets match" "$([ "$missing" = 0 ] && [ "$extra" = 0 ]; echo $?)" \
    "$on_topic distinct on the topic, $in_db in Postgres, $missing missing, $extra extra"
  # Repeats on a topic are expected and harmless: a restarted simulator publishes the same transactions again, and an
  # alert can be republished after a failure. Postgres stores each ID once either way.
  echo "      $1: $duplicates repeated messages on the topic (stored once)"
}

compare "transactions" transactions transactionId "SELECT transaction_id FROM transactions"
compare "alerts" alerts alertId "SELECT alert_id FROM alerts"
unpublished=$(psql "SELECT count(*) FROM alerts WHERE published_at IS NULL")
check "alerts published" "$([ "$unpublished" = 0 ]; echo $?)" "$unpublished alert rows not yet confirmed by Kafka"

echo
if [ "$failures" = 0 ]; then echo "No loss: all checks passed."; else echo "$failures check(s) failed."; exit 1; fi
