# SPIFFE workload identity demo: Add to Cart → Checkout

A small Spring Boot sample showing service-to-service authentication with
[SPIFFE](https://spiffe.io) identities issued by [SPIRE](https://spiffe.io/docs/latest/spire-about/):

- **Service A, `cart-service` ("Add to Cart")** calls **Service B, `checkout-service` ("Checkout")** over REST.
- Every call carries a **JWT-SVID** that SPIRE issues to cart-service's SPIFFE ID.
- Checkout **validates the token** against the SPIRE trust bundle, then **checks the caller's SPIFFE ID** against an allow-list.
- A workload with no registration entry gets no identity and is rejected.

The sample is generic. It uses the reserved trust domain `example.org` and no company-specific names.

```
 cart namespace                          checkout namespace
┌────────────────────────┐  POST /api/   ┌──────────────────────────┐
│ cart-service           │  v1/orders    │ checkout-service         │
│ spiffe://…/ns/cart/…   │ ────────────► │ 1. verify JWT-SVID       │
│ + JWT-SVID (aud=       │ Bearer JWT-SVID│    (signature, aud, exp) │
│   checkout-service)    │               │ 2. allow-list SPIFFE ID  │
└──────────┬─────────────┘               └──────────────────────────┘
           │ Workload API (unix socket)           ▲
           ▼                                      │ rejected: 401 / 403
  SPIRE agent (DaemonSet)  ◄── SPIRE server ◄── rogue namespace (no entry, no SVID)
```

## Repository layout

```
services/cart-service/       Spring Boot 3.5, Java 21, Maven, Dockerfile
services/checkout-service/   Spring Boot 3.5, Java 21, Maven, Dockerfile
deploy/spire/                SPIRE server and agent manifests (namespace spire-system)
deploy/k8s/base/             Namespaces, ServiceAccounts, Deployments, Services for both services
deploy/k8s/overlays/gke/     Overlay for GKE Standard
deploy/k8s/overlays/kubeadm/ Overlay for kubeadm, adds the Artifact Registry pull secret
infra/kubeadm/               Compute Engine VM creation and kubeadm cluster bootstrap
scripts/                     build-and-push, spire-bootstrap (bundle + registration), deploy, validate, demo
docs/API.md                  API reference
docs/openapi/                OpenAPI 3 specs for both services
```

## Identity model

| Item | Value |
|---|---|
| Trust domain | `example.org` (in `deploy/spire/spire-server.yaml`) |
| Cart SPIFFE ID | `spiffe://example.org/ns/cart/sa/cart-service` |
| Checkout SPIFFE ID | `spiffe://example.org/ns/checkout/sa/checkout-service` |
| Registration selectors | `k8s:ns:<namespace>`, `k8s:sa:<service account>` (created by `scripts/spire-bootstrap.sh register`) |
| JWT-SVID audience | `checkout-service` |
| Checkout allow-list | `SPIFFE_ALLOWED_CALLERS=spiffe://example.org/ns/cart/sa/cart-service` |

Services reach the SPIRE agent through a read-only hostPath socket at `/run/spire/agent-sockets`, using `SPIFFE_ENDPOINT_SOCKET`.

## Prerequisites

- Google Cloud project where you can create GKE or Compute Engine resources and Artifact Registry repositories.
- Local tools: `gcloud` (authenticated), `docker` (with buildx), `kubectl`, JDK 21, Maven 3.9+, `kubeconform` (for validation only).
- IAM: your user needs `roles/container.admin` (GKE) or `roles/compute.admin` (kubeadm), `roles/artifactregistry.admin`, and `roles/iap.tunnelResourceAccessor` for the kubeadm IAP tunnel.

Set these once in your shell:

```bash
export PROJECT_ID=your-project-id
export REGION=us-central1
export ZONE=us-central1-a
export AR_REPO=spiffe-demo
gcloud config set project "$PROJECT_ID"
gcloud services enable artifactregistry.googleapis.com container.googleapis.com compute.googleapis.com iap.googleapis.com
gcloud artifacts repositories create "$AR_REPO" --repository-format=docker --location="$REGION"
```

## 1. Build and test

Build and test each service with Maven:

```bash
(cd services/cart-service && mvn -B verify)
(cd services/checkout-service && mvn -B verify)
```

Build the images and push them to Artifact Registry:

```bash
scripts/build-and-push.sh
```

This produces `$REGION-docker.pkg.dev/$PROJECT_ID/$AR_REPO/{cart-service,checkout-service}:0.1.0`. The manifests reference this path through the `AR_PREFIX` placeholder, which `deploy.sh` fills in.

Validate the manifests (needs `kubeconform`):

```bash
scripts/validate-manifests.sh
```

## 2a. Target: GKE Standard cluster

Create the cluster in your project and point `kubectl` at it:

```bash
gcloud container clusters create spiffe-demo \
  --zone "$ZONE" \
  --num-nodes 2 \
  --machine-type e2-standard-4

gcloud container clusters get-credentials spiffe-demo --zone "$ZONE"
```

Deploy SPIRE and the services:

```bash
scripts/deploy.sh gke
```

GKE nodes pull from Artifact Registry in the same project using the node service account. The default Compute Engine service account has the reader role it needs in most projects. If image pulls fail, grant `roles/artifactregistry.reader` to the node service account.

Run the demo (section 3).

## 2b. Target: self-managed kubeadm cluster on Compute Engine

This builds a three-node kubeadm cluster (one control plane, two workers) on Ubuntu 24.04 VMs in your project. It uses no public IPs; you reach the cluster through IAP.

Create the VMs. Each runs `infra/kubeadm/bootstrap-node.sh` as its startup script (containerd, kubeadm, kubelet, kubectl). Wait 3–5 minutes after this step:

```bash
infra/kubeadm/create-vms.sh
```

Initialize the control plane, install Calico, join the workers, and write a kubeconfig:

```bash
infra/kubeadm/init-cluster.sh
```

The kubeconfig points at `https://127.0.0.1:6443`. Open a second terminal and keep this IAP tunnel running:

```bash
gcloud compute start-iap-tunnel spiffe-cp-1 6443 \
  --local-host-port=localhost:6443 \
  --zone "$ZONE" --project "$PROJECT_ID"
```

In the first terminal, check the cluster:

```bash
export KUBECONFIG="$HOME/.kube/spiffe-kubeadm.conf"
kubectl get nodes -o wide
```

Build and push the images, then deploy. The deploy script creates an `artifact-registry` pull secret from your gcloud access token, because kubeadm nodes do not get Artifact Registry credentials automatically:

```bash
scripts/build-and-push.sh
scripts/deploy.sh kubeadm
```

The pull secret uses a short-lived access token, which expires in about an hour. If pods later show `ImagePullBackOff`, run `scripts/deploy.sh kubeadm` again to refresh it. For a longer-lived setup, use a kubelet image credential provider.

Run the demo (section 3).

## 3. Demo script

`scripts/demo.sh` runs three checks against the current cluster:

```bash
scripts/demo.sh
```

1. **Allowed call (expect `201`).** It port-forwards to cart-service, adds an item, and checks out. Cart-service fetches a JWT-SVID for the `checkout-service` audience and calls checkout. Checkout logs `Authenticated caller spiffe://example.org/ns/cart/sa/cart-service`.
2. **Unregistered workload, no credential (expect `401`).** It runs a `curl` pod in the `rogue` namespace. That namespace has no registration entry, so SPIRE issues it no identity. The request has no bearer token, so checkout returns 401.
3. **Unregistered workload, forged token (expect `401`).** The same pod sends an unsigned `alg=none` JWT that claims to be cart-service. Checkout rejects the signature.

The script ends by printing the checkout log lines for accepted and rejected calls.

To inspect the registration entries by hand:

```bash
kubectl -n spire-system exec deploy/spire-server -- /opt/spire/bin/spire-server entry show
```

To show the 403 path (a valid identity that is not on the allow-list), change `SPIFFE_ALLOWED_CALLERS` in the checkout deployment, or add another registered workload and call checkout from it.

## Re-running and cleanup

- After adding a node, run `scripts/spire-bootstrap.sh register` so the new agent gets entries.
- After a SPIRE server restart, run `scripts/spire-bootstrap.sh all`. The demo keeps SPIRE state in an `emptyDir`.
- Remove the demo workloads and SPIRE:

  ```bash
  kubectl delete namespace cart checkout rogue spire-system --ignore-not-found
  ```

- GKE: `gcloud container clusters delete spiffe-demo --zone "$ZONE"`
- kubeadm: `gcloud compute instances delete spiffe-cp-1 spiffe-worker-1 spiffe-worker-2 --zone "$ZONE"` (adjust for your `WORKERS`), then the two `spiffe-demo-allow-*` firewall rules.

## Production notes and known limits

This is a demonstration. Before using the pattern for real workloads:

- **SPIRE state.** The server uses `emptyDir` and SQLite. Use a database and a persistent volume with a high-availability server.
- **Kubelet verification.** The workload attestor sets `skip_kubelet_verification = true`. Configure `kubelet_ca_path` instead.
- **Trust domain and cluster name.** Replace `example.org` and `demo-cluster` with your own values in `deploy/spire/` and in the service environment variables.
- **Registration.** The demo creates entries with a script. Use the SPIRE controller manager, or the Helm charts, to keep entries in sync with Kubernetes objects.
- **Image pull credentials on kubeadm.** Use a kubelet image credential provider instead of a short-lived access-token secret.
- **mTLS.** This sample authenticates with JWT-SVIDs over HTTP. For transport encryption, put the services behind an mTLS-capable proxy, or switch to X.509-SVIDs with a TLS-aware client.
- **Versions.** SPIRE images are pinned to `1.12.4`. Check for the current release before deploying, and update the image tags in `deploy/spire/`.
- **Not run against a live cluster.** Both Maven builds and their unit tests pass, and the manifests validate with kubeconform. The end-to-end demo was not run here, because it needs a live GKE or kubeadm cluster.

## Quick reference

| Task | Command |
|---|---|
| Test both services | `(cd services/cart-service && mvn -B verify)` and the same for `checkout-service` |
| Build and push images | `scripts/build-and-push.sh` |
| Validate manifests | `scripts/validate-manifests.sh` |
| Deploy to GKE | `scripts/deploy.sh gke` |
| Deploy to kubeadm | `scripts/deploy.sh kubeadm` |
| Run the demo | `scripts/demo.sh` |
| API reference | [docs/API.md](docs/API.md) |
