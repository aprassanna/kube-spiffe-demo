#!/usr/bin/env bash
# Creates the Compute Engine VMs for a self-managed kubeadm cluster in your own project.
#
# Required: PROJECT_ID
# Optional: ZONE (default us-central1-a), WORKERS (default 2), MACHINE_TYPE (default e2-standard-4)
#
# Each VM runs bootstrap-node.sh as its startup script, which installs containerd, kubeadm,
# kubelet and kubectl. Then run infra/kubeadm/init-cluster.sh.
set -euo pipefail

cd "$(dirname "$0")"

PROJECT_ID="${PROJECT_ID:?set PROJECT_ID}"
ZONE="${ZONE:-us-central1-a}"
WORKERS="${WORKERS:-2}"
MACHINE_TYPE="${MACHINE_TYPE:-e2-standard-4}"

gcloud config set project "$PROJECT_ID" >/dev/null

# SSH through IAP (no public IPs needed).
gcloud compute firewall-rules create spiffe-demo-allow-iap-ssh \
  --network=default --direction=INGRESS --allow=tcp:22 \
  --source-ranges=35.235.240.0/20 --quiet 2>/dev/null || echo "firewall rule spiffe-demo-allow-iap-ssh already exists"

# API server access through an IAP tunnel from your workstation.
gcloud compute firewall-rules create spiffe-demo-allow-iap-apiserver \
  --network=default --direction=INGRESS --allow=tcp:6443 \
  --source-ranges=35.235.240.0/20 --target-tags=spiffe-demo-control-plane --quiet 2>/dev/null \
  || echo "firewall rule spiffe-demo-allow-iap-apiserver already exists"

create_vm() {
  local name="$1" tag="$2"
  gcloud compute instances create "$name" \
    --zone="$ZONE" \
    --machine-type="$MACHINE_TYPE" \
    --image-family=ubuntu-2404-lts-amd64 \
    --image-project=ubuntu-os-cloud \
    --boot-disk-size=50GB \
    --tags="$tag" \
    --scopes=cloud-platform \
    --metadata-from-file=startup-script=bootstrap-node.sh
}

create_vm spiffe-cp-1 spiffe-demo-control-plane

for i in $(seq 1 "$WORKERS"); do
  create_vm "spiffe-worker-$i" spiffe-demo-worker
done

echo "Created control plane spiffe-cp-1 and $WORKERS worker(s) in $ZONE."
echo "Wait 3-5 minutes for the startup scripts to finish, then run infra/kubeadm/init-cluster.sh."
