# Running this on GKE

A minimal, cost-conscious path to a real GKE deployment of the same stack
`scripts/k8s-up.sh` runs locally on kind: a zonal cluster with a single
spot node pool (cheap, preemptible), one Artifact Registry repo for the 7
service images, and a $5 budget alert so a forgotten demo cluster can't
run away on cost. Not hardened for production — same dev-only secrets
posture as the rest of this repo (see [SECURITY.md](../SECURITY.md)).

## One-time setup

Replace `my-project` with your actual GCP project ID throughout.

```bash
# Set once per shell session
export GCP_PROJECT_ID=my-project
export GKE_ZONE=us-central1-a
export AR_REGION=us-central1
gcloud config set project "${GCP_PROJECT_ID}"

# Enable the APIs this needs
gcloud services enable container.googleapis.com artifactregistry.googleapis.com

# Artifact Registry repo for the 7 service images
gcloud artifacts repositories create credit-card \
  --repository-format=docker \
  --location="${AR_REGION}" \
  --description="Credit Card Payment Gateway service images"

# Zonal cluster, no default node pool (--num-nodes 0), so the only nodes
# that ever run are the spot pool created below
gcloud container clusters create credit-card-demo \
  --zone "${GKE_ZONE}" \
  --num-nodes 0

# Spot node pool: 2 x e2-medium is enough for this stack's resource
# requests (~4.3Gi/6.5Gi across infra + 7 services at 2 replicas each --
# same budget as the kind cluster, see k8s/base/*/deployment.yaml)
gcloud container node-pools create spot-pool \
  --cluster credit-card-demo \
  --zone "${GKE_ZONE}" \
  --spot \
  --machine-type e2-medium \
  --num-nodes 2

# Point kubectl at it
gcloud container clusters get-credentials credit-card-demo --zone "${GKE_ZONE}"

# $5 budget alert -- replace BILLING_ACCOUNT_ID with your own
# (gcloud billing accounts list)
gcloud billing budgets create \
  --billing-account=BILLING_ACCOUNT_ID \
  --display-name="credit-card-demo" \
  --budget-amount=5 \
  --threshold-rule=percent=0.5 \
  --threshold-rule=percent=1.0
```

## Deploy

```bash
GCP_PROJECT_ID=my-project ./scripts/gke-deploy.sh
```

Builds all 7 service jars, builds and pushes their images to Artifact
Registry, then applies the full stack via `k8s/overlays/gke` (the same
manifests as kind, at the base's `replicas: 2`, with images rewritten to
the registry path instead of a local-only one). See
[k8s/overlays/gke/kustomization.yaml.tmpl](../k8s/overlays/gke/kustomization.yaml.tmpl)
for exactly what it changes from the base.

## Between demos

The spot node pool is the main cost while idle (the first zonal cluster
per billing account skips GKE's cluster management fee; Artifact
Registry storage at this scale is a few cents). Scale it to 0 when
you're not using it, back to 2 when you are — nothing else needs to be
re-applied; Kubernetes reschedules every existing Deployment once nodes
are Ready again.

```bash
GCP_PROJECT_ID=my-project ./scripts/demo-down.sh   # 0 nodes
GCP_PROJECT_ID=my-project ./scripts/demo-up.sh     # back to 2
```

## Tear down entirely

```bash
gcloud container clusters delete credit-card-demo --zone "${GKE_ZONE}"
gcloud artifacts repositories delete credit-card --location="${AR_REGION}"
```
