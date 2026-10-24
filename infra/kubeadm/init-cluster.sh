#!/usr/bin/env bash
# Initializes the kubeadm control plane, installs Calico, joins the workers, and writes a
# kubeconfig that reaches the API server through an IAP tunnel on localhost:6443.
#
# Required: PROJECT_ID
# Optional: ZONE (default us-central1-a), WORKERS (default 2), KUBECONFIG_OUT (default ~/.kube/spiffe-kubeadm.conf)
#
# Run create-vms.sh first. Keep the tunnel running while you use kubectl (the script prints the command).
set -euo pipefail

PROJECT_ID="${PROJECT_ID:?set PROJECT_ID}"
ZONE="${ZONE:-us-central1-a}"
WORKERS="${WORKERS:-2}"
KUBECONFIG_OUT="${KUBECONFIG_OUT:-$HOME/.kube/spiffe-kubeadm.conf}"
CP="spiffe-cp-1"
CALICO_VERSION="v3.30.3"

ssh_to() { gcloud compute ssh "$1" --zone="$ZONE" --project="$PROJECT_ID" --tunnel-through-iap --quiet --command "$2"; }

echo "== Initializing control plane on $CP"
ssh_to "$CP" "sudo kubeadm init --pod-network-cidr=192.168.0.0/16 --apiserver-cert-extra-sans=127.0.0.1"
ssh_to "$CP" "mkdir -p \$HOME/.kube && sudo cp /etc/kubernetes/admin.conf \$HOME/.kube/config && sudo chown \$(id -u):\$(id -g) \$HOME/.kube/config"

echo "== Installing Calico $CALICO_VERSION"
ssh_to "$CP" "kubectl create -f https://raw.githubusercontent.com/projectcalico/calico/${CALICO_VERSION}/manifests/tigera-operator.yaml || true"
ssh_to "$CP" "curl -fsSL https://raw.githubusercontent.com/projectcalico/calico/${CALICO_VERSION}/manifests/custom-resources.yaml | kubectl create -f - || true"

echo "== Joining workers"
JOIN_CMD="$(ssh_to "$CP" "sudo kubeadm token create --print-join-command")"
for i in $(seq 1 "$WORKERS"); do
  ssh_to "spiffe-worker-$i" "sudo $JOIN_CMD"
done

echo "== Writing kubeconfig to $KUBECONFIG_OUT"
mkdir -p "$(dirname "$KUBECONFIG_OUT")"
ssh_to "$CP" "sudo cat /etc/kubernetes/admin.conf" \
  | sed 's#https://[^:]*:6443#https://127.0.0.1:6443#' > "$KUBECONFIG_OUT"
chmod 600 "$KUBECONFIG_OUT"

cat <<EOF

Cluster is up. Next steps:
  1. In a separate terminal, keep this tunnel open:
       gcloud compute start-iap-tunnel spiffe-cp-1 6443 --local-host-port=localhost:6443 --zone=$ZONE --project=$PROJECT_ID
  2. Check nodes:
       KUBECONFIG=$KUBECONFIG_OUT kubectl get nodes -o wide
EOF
