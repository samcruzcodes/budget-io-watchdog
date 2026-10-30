#!/usr/bin/env bash
# Brings the whole stack up on a local kind cluster and waits until every app pod is Ready.
#   scripts/k8s-up.sh             Kafka, Postgres, the simulator, and the watchdog
#   scripts/k8s-up.sh --with-ui   the same, plus Kafka UI
# Run scripts/install-tools.sh once first. Every kubectl call names the kind cluster's context, so this can't touch any
# other cluster your kubeconfig knows about.
set -euo pipefail

cd "$(dirname "$0")/.."
KIND=.tools/bin/kind
KUBECTL=".tools/bin/kubectl --context kind-watchdog"
CLUSTER=watchdog
WITH_UI=false
[ "${1:-}" = "--with-ui" ] && WITH_UI=true

# The pinned tools, not whatever is on the PATH.
if [ ! -x .tools/bin/kind ] || [ ! -x .tools/bin/kubectl ]; then
  echo "Run scripts/install-tools.sh first." >&2
  exit 1
fi
.tools/bin/kind version | grep -q "v0.33.0" || { echo "Expected kind v0.33.0" >&2; exit 1; }
.tools/bin/kubectl version --client | grep -q "v1.37.1" || { echo "Expected kubectl v1.37.1" >&2; exit 1; }

echo "== Cluster"
if $KIND get clusters | grep -qx "$CLUSTER"; then
  echo "kind cluster '$CLUSTER' already exists; reusing it"
else
  $KIND create cluster --config k8s/kind-config.yaml
fi

echo "== Images"
# Built with the same Dockerfiles as Compose, then copied into the cluster's node, so there's no registry.
docker build -t watchdog:dev -f watchdog-service/Dockerfile .
docker build -t simulator:dev -f simulator/Dockerfile .
# Only our two images. The node pulls Kafka, Postgres, and Kafka UI itself: loading those multi-platform images from
# Docker Desktop fails with "content digest not found", because only this Mac's platform was downloaded.
$KIND load docker-image --name "$CLUSTER" watchdog:dev simulator:dev

echo "== Namespace, Secret, ConfigMap"
$KUBECTL apply -f k8s/namespace.yaml
# Created here, with the same dev-only password as Compose, so no Secret file is committed. "apply" makes reruns safe.
$KUBECTL -n watchdog create secret generic postgres-credentials --from-literal=password=watchdog \
  --dry-run=client -o yaml | $KUBECTL apply -f -
# The simulator's settings come from the same preset file Compose uses.
$KUBECTL -n watchdog create configmap simulator-config --from-env-file=config/simulator.demo.env \
  --dry-run=client -o yaml | $KUBECTL apply -f -

echo "== Kafka and Postgres"
$KUBECTL apply -f k8s/kafka.yaml -f k8s/postgres.yaml
$KUBECTL apply -f k8s/kafka-topics-job.yaml
$KUBECTL -n watchdog wait --for=condition=complete job/kafka-topics --timeout=5m

echo "== Apps"
$KUBECTL apply -f k8s/simulator.yaml -f k8s/watchdog.yaml
$WITH_UI && $KUBECTL apply -f k8s/kafka-ui.yaml

# A selector, not --all: the finished topics Job's pod is never Ready, and waiting on it would hang.
selector='app in (kafka,postgres,simulator,watchdog)'
$WITH_UI && selector='app in (kafka,postgres,simulator,watchdog,kafka-ui)'
$KUBECTL -n watchdog wait --for=condition=Ready pod -l "$selector" --timeout=5m
$KUBECTL -n watchdog get pods
echo
echo "Ready. The API: .tools/bin/kubectl --context kind-watchdog -n watchdog port-forward svc/watchdog 8082:8082"
