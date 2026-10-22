#!/usr/bin/env bash
# Tears down the local kind cluster created by scripts/k8s-up.sh.
# Leaves locally-built credit-card/*:local images and the separate
# docker-compose stack untouched.

set -euo pipefail

CLUSTER_NAME="credit-card-kind"

if kind get clusters 2>/dev/null | grep -qx "${CLUSTER_NAME}"; then
  kind delete cluster --name "${CLUSTER_NAME}"
  echo "Deleted kind cluster '${CLUSTER_NAME}'."
else
  echo "No kind cluster named '${CLUSTER_NAME}' found -- nothing to do."
fi
