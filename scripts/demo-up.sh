#!/usr/bin/env bash
# Scales the GKE demo cluster's spot node pool up to 2 nodes. The control
# plane (zonal cluster) keeps every Deployment/Service/Secret regardless of
# node count, so workloads reschedule on their own once nodes are Ready --
# no re-apply needed.
#
# Required env vars: GCP_PROJECT_ID
# Optional env vars: GKE_CLUSTER (default credit-card-demo), GKE_ZONE
#   (default us-central1-a), GKE_NODE_POOL (default spot-pool)
#
# Usage: GCP_PROJECT_ID=my-project scripts/demo-up.sh
# Scale back down when you're done: scripts/demo-down.sh

set -euo pipefail

command -v gcloud &>/dev/null || { echo "Missing prerequisite: gcloud" >&2; exit 1; }
: "${GCP_PROJECT_ID:?Set GCP_PROJECT_ID to your GCP project (see docs/GKE.md)}"
GKE_CLUSTER="${GKE_CLUSTER:-credit-card-demo}"
GKE_ZONE="${GKE_ZONE:-us-central1-a}"
GKE_NODE_POOL="${GKE_NODE_POOL:-spot-pool}"

echo "Scaling ${GKE_CLUSTER}/${GKE_NODE_POOL} (${GKE_ZONE}) to 2 nodes..."
gcloud container clusters resize "${GKE_CLUSTER}" \
  --zone "${GKE_ZONE}" \
  --node-pool "${GKE_NODE_POOL}" \
  --num-nodes 2 \
  --project "${GCP_PROJECT_ID}" \
  --quiet

echo "Waiting for pods to reschedule..."
kubectl wait --for=condition=ready pod --all -n credit-card --timeout=300s

echo "Demo cluster is up."
