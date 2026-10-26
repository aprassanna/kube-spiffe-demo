# SPIFFE workload identity demo: Add to Cart → Checkout (mTLS)

A small Spring Boot sample showing service-to-service **mutual TLS** with **X.509-SVID**
identities issued by [SPIRE](https://spiffe.io/docs/latest/spire-about/) on Kubernetes.

- **Service A, `cart-service` ("Add to Cart")** calls **Service B, `checkout-service` ("Checkout")** over HTTPS.
- Both sides present X.509 certificates. SPIRE issues them to each workload's SPIFFE ID, based on its Kubernetes ServiceAccount.
- Checkout requires a client certificate in the TLS handshake. It checks the caller's SPIFFE ID against an allow-list.
- Cart accepts the server only if the server presents the checkout SPIFFE ID.
- A workload with no SPIRE registration has no certificate, so the handshake fails.
- Certificates rotate automatically. Both services poll the SPIRE agent and reload their TLS material.

The sample is generic. It uses the reserved trust domain `example.org` and has no company-specific names.

```
 cart namespace                              checkout namespace
┌────────────────────────────┐   HTTPS 443    ┌──────────────────────────────┐
│ cart-service               │   mTLS         │ checkout-service             │
│ SVID: spiffe://…/ns/cart/… │ ─────────────► │ 1. TLS handshake: client     │
│ accepts only checkout's ID │ ◄───────────── │    cert chains to SPIRE CA   │
└─────────────┬──────────────┘                │ 2. allow-list on SPIFFE ID   │
              │ Workload API (unix socket)    └──────────────────────────────┘
              ▼
   SPIRE agent (DaemonSet) ◄── SPIRE server (CA + registration)
                                      ▲
   rogue namespace: no entry, no SVID ┘  → handshake refused
```

## What is authenticated, and how

| Hop | Client identity | Server identity | Enforced by |
|---|---|---|---|
| Cart → Checkout | cart's X.509-SVID (`…/ns/cart/sa/cart-service`) | checkout's X.509-SVID (`…/ns/checkout/sa/checkout-service`) | Checkout: TLS handshake (`client-auth: need`) plus SPIFFE allow-list. Cart: `ServerIdentityTrustManager` requires the expected SPIFFE ID. |
| Anything → Cart | none (demo entry point) | none (plain HTTP) | Out of scope for this demo. |

The PKI is SPIRE's built-in CA. SPIRE attests each node with the Kubernetes `k8s_psat` plugin, and attests each pod with the `k8s` workload attestor (namespace and ServiceAccount). Both attestations come from the Kubernetes API, so no static certificates are stored in the cluster.

## Repository layout

```
services/cart-service/       Spring Boot 3.5, Java 21, Maven, mTLS client
services/checkout-service/   Spring Boot 3.5, Java 21, Maven, mTLS server + SPIFFE allow-list
images/java-runtime/         Base runtime image spec (distroless Java 21, nonroot)
cloudbuild.yaml              Builds the base image, then both services, into Artifact Registry
deploy/spire/                SPIRE server and agent manifests (namespace spire-system)
deploy/k8s/base/             Namespaces, ServiceAccounts, Deployments, Services for both services
deploy/k8s/overlays/gke/     Overlay for GKE Standard
deploy/k8s/overlays/kubeadm/ Overlay for kubeadm, adds the Artifact Registry pull secret
infra/kubeadm/               Compute Engine VM creation and kubeadm cluster bootstrap
scripts/                     build, SPIRE bootstrap, deploy, validate, demo (assertions), test-all
docs/API.md                  API reference
docs/openapi/                OpenAPI 3 specs for both services
```

## Prerequisites

- A Google Cloud project where you can create GKE or Compute Engine resources, Artifact Registry repositories, and Cloud Build jobs.
- Local tools: `gcloud` (authenticated), `kubectl` with the `gke-gcloud-auth-plugin`, JDK 21, Maven 3.9+, and `kubeconform` for manifest validation.
- IAM: `roles/container.admin` (GKE) or `roles/compute.admin` (kubeadm), `roles/artifactregistry.admin`, `roles/cloudbuild.builds.editor`, and `roles/iap.tunnelResourceAccessor` for kubeadm.

Set these once in your shell:

```bash
export PROJECT_ID=your-project-id
export REGION=us-central1
export ZONE=us-central1-a
export AR_REPO=spiffe-demo
gcloud config set project "$PROJECT_ID"
gcloud services enable artifactregistry.googleapis.com container.googleapis.com cloudbuild.googleapis.com compute.googleapis.com iap.googleapis.com
gcloud artifacts repositories create "$AR_REPO" --repository-format=docker --location="$REGION"
```

## 1. Test and build

Run every offline check (unit tests for both services and manifest validation):

```bash
RUN_E2E=0 scripts/test-all.sh
```

Build the base runtime image and both services in Cloud Build, then push them to Artifact Registry. No local Docker daemon is needed:

```bash
scripts/build-and-push.sh
```

`cloudbuild.yaml` builds `images/java-runtime` first (distroless Java 21, nonroot). It then builds each service with `BASE_IMAGE` pointing at that image. The images are `$REGION-docker.pkg.dev/$PROJECT_ID/$AR_REPO/{java-runtime,cart-service,checkout-service}:0.1.0`.

## 2a. Target: GKE Standard cluster

```bash
gcloud container clusters create spiffe-demo \
  --zone "$ZONE" --num-nodes 2 --machine-type e2-standard-4

gcloud container clusters get-credentials spiffe-demo --zone "$ZONE"

scripts/deploy.sh gke
```

GKE nodes pull from Artifact Registry in the same project through the node service account, which needs `roles/artifactregistry.reader`.

## 2b. Target: self-managed kubeadm cluster on Compute Engine

```bash
infra/kubeadm/create-vms.sh      # control plane + 2 workers, Ubuntu 24.04, no public IPs
infra/kubeadm/init-cluster.sh    # kubeadm init, Calico, join workers, write kubeconfig
```

Keep this IAP tunnel open in a second terminal while you use kubectl:

```bash
gcloud compute start-iap-tunnel spiffe-cp-1 6443 \
  --local-host-port=localhost:6443 --zone "$ZONE" --project "$PROJECT_ID"
```

Then deploy:

```bash
export KUBECONFIG="$HOME/.kube/spiffe-kubeadm.conf"
scripts/deploy.sh kubeadm
```

The kubeadm target creates an `artifact-registry` pull secret from your gcloud access token. That token expires in about an hour. If pods show `ImagePullBackOff`, run `scripts/deploy.sh kubeadm` again.

## 3. Deploy order

`scripts/deploy.sh` applies things in this order, because each step depends on the one before:

1. SPIRE server, then `spire-bootstrap.sh bundle` publishes the trust bundle as a ConfigMap.
2. SPIRE agents (one per node), then `spire-bootstrap.sh register` creates a registration entry for each agent and workload. Each entry maps `k8s:ns` and `k8s:sa` to a SPIFFE ID.
3. Namespaces, then the two services.

## 4. Demo and assertions

```bash
scripts/demo.sh
```

Or run everything, including the live demo:

```bash
scripts/test-all.sh
```

`demo.sh` asserts each step and exits non-zero on failure:

1. **Allowed.** It port-forwards to cart and adds an item (expect `201`). It checks out, which makes cart call checkout over mTLS (expect `201`). The order's `placedBy` must be the cart SPIFFE ID, and the checkout log must show the authenticated caller.
2. **Rejected, no certificate.** A pod in the `rogue` namespace, with no registration and no SPIRE socket, calls checkout with no client certificate. The TLS handshake must fail: no HTTP status, and a non-zero curl exit.
3. **Rejected, untrusted certificate.** The same pod presents a self-signed certificate claiming the cart SPIFFE ID. SPIRE did not issue it, so the handshake must fail.

A valid SPIFFE identity that is not on the allow-list gets `403` at the application layer. The unit tests cover that case (`SpiffeAuthorizationFilterTest`), and the live demo does not exercise it.

## Re-running and cleanup

- After adding a node, run `scripts/spire-bootstrap.sh register`.
- After a SPIRE server restart, run `scripts/spire-bootstrap.sh all`. The demo keeps SPIRE state in an `emptyDir`.
- Remove the workloads and SPIRE: `kubectl delete namespace cart checkout rogue spire-system --ignore-not-found`
- GKE: `gcloud container clusters delete spiffe-demo --zone "$ZONE"`
- kubeadm: `gcloud compute instances delete spiffe-cp-1 spiffe-worker-1 spiffe-worker-2 --zone "$ZONE"`, then delete the two `spiffe-demo-allow-*` firewall rules.

## Production notes and known limits

- **SPIRE state.** The server uses `emptyDir` and SQLite. Use a database and a persistent volume, with a highly available server.
- **Kubelet verification.** The workload attestor sets `skip_kubelet_verification = true`. Configure `kubelet_ca_path` instead.
- **Upstream CA.** SPIRE uses its own self-signed CA here. To anchor SPIRE to an enterprise or Kubernetes-based PKI, configure an `UpstreamAuthority` plugin (for example cert-manager or a Vault PKI).
- **Trust domain and cluster name.** Replace `example.org` and `demo-cluster` with your own values in `deploy/spire/` and in the services' environment variables.
- **Registration.** Entries are created by script. Use the SPIRE controller manager to keep them in sync with Kubernetes objects.
- **Keystore password.** The in-memory PKCS12 stores use a fixed demo password. Their contents never leave the process.
- **Versions.** SPIRE images are pinned to `1.12.4`. Check for the current release before deploying.

## Verified live run

Run on GKE Standard (`spiffe-demo`, us-central1-a, 2 × e2-standard-4), Kubernetes 1.36, SPIRE 1.12.4, against images built by Cloud Build:

```
PASS  add item to cart returns 201
PASS  checkout returns 201
PASS  order records the mTLS caller SPIFFE ID
PASS  checkout log shows the authenticated caller   (spiffe://example.org/ns/cart/sa/cart-service via mTLS)
PASS  no-certificate call fails at the TLS handshake   (curl exit 56)
PASS  self-signed certificate fails at the TLS handshake (curl exit 56)
== Result: 6 passed, 0 failed
```

The kubeadm target has not been run.

## Inspect the identities and the handshake

`scripts/inspect-mtls.sh` prints the console links for this deployment, lists the SPIRE agents and registration entries, and describes the tokens in play. It also starts a debug pod under the `cart-service` ServiceAccount, fetches its X.509-SVID from the agent, and runs a live mTLS handshake against checkout (with and without the certificate). The pod is deleted on exit.

```bash
PROJECT_ID=your-project-id scripts/inspect-mtls.sh
```

## Dormancy: pause the cluster to stop the cost

`scripts/cluster-power.sh` puts the cluster to sleep and wakes it again:

```bash
PROJECT_ID=your-project-id scripts/cluster-power.sh status
PROJECT_ID=your-project-id scripts/cluster-power.sh off            # scale nodes to 0 (node and disk charges stop)
PROJECT_ID=your-project-id scripts/cluster-power.sh off --delete   # delete the cluster (cluster cost goes to 0)
PROJECT_ID=your-project-id scripts/cluster-power.sh on             # scale up or recreate, then redeploy
```

`off` keeps the cluster, so the GKE control-plane fee (about /month) can still apply unless the free tier covers it. `off --delete` removes that fee too. Images stay in Artifact Registry. `on` redeploys everything, including the SPIRE trust bundle and registration entries, because the server state is not persisted.

## Run locally on your Mac (kind in Colima)

The same SPIRE setup and demo run on a local single-node cluster. Nothing is billed.

```bash
brew install colima docker kind helm
colima start --cpu 6 --memory 8 --disk 40 --vm-type vz
kind create cluster --name spiffe-local

docker build -t spiffe-local/cart-service:0.1.0 services/cart-service
docker build -t spiffe-local/checkout-service:0.1.0 services/checkout-service
kind load docker-image spiffe-local/cart-service:0.1.0 --name spiffe-local
kind load docker-image spiffe-local/checkout-service:0.1.0 --name spiffe-local

scripts/deploy.sh local     # refuses to run unless the context is kind-spiffe-local
scripts/demo.sh
```

Stop the VM when you are done. Stopping Colima frees its RAM and the cluster state is kept:

```bash
colima stop
```

Delete the cluster as well with `kind delete cluster --name spiffe-local`. The local cluster runs on arm64 and Apple Silicon, so the `grpc-netty-linux` transport is exercised there too.

## Quick reference

| Task | Command |
|---|---|
| Offline checks | `RUN_E2E=0 scripts/test-all.sh` |
| Build images in Cloud Build | `scripts/build-and-push.sh` |
| Deploy to GKE | `scripts/deploy.sh gke` |
| Deploy to kubeadm | `scripts/deploy.sh kubeadm` |
| Run the demo | `scripts/demo.sh` |
| Everything | `scripts/test-all.sh` |
| Pause or resume the cluster | `PROJECT_ID=... scripts/cluster-power.sh off|on` |
| Inspect SVIDs and the handshake | `PROJECT_ID=... scripts/inspect-mtls.sh` |
| API reference | [docs/API.md](docs/API.md) |
