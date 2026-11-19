# Tatara runners on Kubernetes (VKE) — AWAI-3542

T2 runners for the coordinator, run as a StatefulSet on a Kubernetes cluster
(Vultr VKE `awai-tatara-ci`, nrt, x86_64). No Nix and no image registry. They add capacity
next to the Mac mini runners and take part in the same quorum.

| File | Role |
|---|---|
| `tatara-runner.yaml` | ConfigMap (boot script) and StatefulSet `tatara-runner` in namespace `tatara` |
| `scaler.yaml` | Queue-based scaler (Deployment `tatara-scaler`, RBAC limited to `statefulsets/scale` of `tatara-runner`) |
| `provision-host.sh` | Same pinned tools for a plain Linux x86_64 host with systemd, if a VM is used instead |

## How it works

**Identity.** Pod `tatara-runner-N` uses Secret `tatara-runner-identity-N` (N = 0..2). Every replica
therefore has its own DID, and the coordinator counts them separately. Those three DIDs are in the
coordinator `:allow` set. Scaling between 1 and 3 replicas needs no coordinator change.

**Network.** A `tailscale/tailscale:v1.104.7` sidecar joins the tailnet as `vke-tatara-runner-N`, using
the ephemeral, reusable auth key in Secret `tailscale-auth`. It needs `NET_ADMIN` and `/dev/net/tun`.
The runner reaches the coordinator at `http://100.108.223.94:8790`. Steps run with
`:network-enforcement :unshare`, which is why the runner container has `SYS_ADMIN`.

**Tools.** The runner container is `ubuntu:24.04`. At start it installs, checking each digest:
- Node 22.23.5;
- Babashka 1.12.218;
- nbb 1.4.208 (the npm integrity pinned in network-awai-apex `ci/otehon-nbb.nix`);
- app-tatara at `TATARA_REV`.

It also installs `iproute2`: `:unshare` steps bring up loopback with `ip link set lo up`, and without it every
`:network :none` step fails. It then runs `bb run runner.edn`.

**Scaling.** `tatara-scaler` (`bb scale`, `tatara.runner.scaler`) polls the coordinator's `GET /v1/runs` every 30 s.
- Demand is the number of replica jobs that are `:queued` or `:leased` in `:pending` runs.
- It scales up at once, to that number and at most 3.
- It scales down to the minimum (0) only after 600 s with no open job, so no pod is removed while it holds a lease.
- A failed coordinator or API call leaves the count unchanged.
- The node pool autoscaler adds or removes nodes to fit the pods.

## Set up

```sh
kubectl create namespace tatara
# Tailscale auth key (reusable, ephemeral), read from the password manager without printing it:
kubectl -n tatara create secret generic tailscale-auth --from-literal=TS_AUTHKEY="$(op read 'op://…/credential')"
# One identity per ordinal (bb keygen in packages/tatara-coordinator); add each DID to the coordinator :allow set:
kubectl -n tatara create secret generic tatara-runner-identity-0 --from-file=identity.edn=<file>
kubectl apply -f tatara-runner.yaml
kubectl apply -f scaler.yaml
```

The VKE node pool `tatara-runners` (vc2-4c-8gb) has the autoscaler set to 1–3 nodes. Each runner
requests 1.5 CPU and 3 GiB, so two fit on a node; a third replica brings up a second node.

## State on 2026-11-19

- Pod 0 joined the tailnet and polls the coordinator. Its DID was added to `:allow`.
- Its first verdict, on app-tatara main `3a5b11fe`, was `:failed` against the mini's `:passed` (split threshold).
  The cause was the missing `ip` command (above), fixed in #19.
- After #19, the scaler went from 0 to 1 replica within one poll of the run for `6c3b3aac`. The Mac minis had
  already taken both jobs before the pod finished booting (about 1–2 min).
