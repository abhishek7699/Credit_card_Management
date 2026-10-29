#!/usr/bin/env bash
# Scales the GKE demo cluster's spot node pool down to 0 nodes -- the
# cheapest way to leave the cluster "available" between demos: the control
# plane, Artifact Registry images, and every Deployment/Service/Secret stay
# intact, you're just not paying for any running nodes. scripts/demo-up.sh
# brings it back.
#
# Required env vars: GCP_PROJECT_ID
# Optional env vars: GKE_CLUSTER (default credit-card-demo), GKE_ZONE
#   (default us-central1-a), GKE_NODE_POOL (default spot-pool)
#
# Usage: GCP_PROJECT_ID=my-project scripts/demo-down.sh

set -euo pipefail

command -v gcloud &>/dev/null || { echo "Missing prerequisite: gcloud" >&2; exit 1; }
: "${GCP_PROJECT_ID:?Set GCP_PROJECT_ID to your GCP project (see docs/GKE.md)}"
GKE_CLUSTER="${GKE_CLUSTER:-credit-card-demo}"
GKE_ZONE="${GKE_ZONE:-us-central1-a}"
GKE_NODE_POOL="${GKE_NODE_POOL:-spot-pool}"

echo "Scaling ${GKE_CLUSTER}/${GKE_NODE_POOL} (${GKE_ZONE}) to 0 nodes..."
gcloud container clusters resize "${GKE_CLUSTER}" \
  --zone "${GKE_ZONE}" \
  --node-pool "${GKE_NODE_POOL}" \
  --num-nodes 0 \
  --project "${GCP_PROJECT_ID}" \
  --quiet

echo "Demo cluster is down (0 nodes). Bring it back with scripts/demo-up.sh."
