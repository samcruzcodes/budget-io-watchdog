#!/usr/bin/env bash
# Deletes the kind cluster and everything in it (the Postgres volume too). Nothing is left running afterwards.
set -euo pipefail
cd "$(dirname "$0")/.."
.tools/bin/kind delete cluster --name watchdog
