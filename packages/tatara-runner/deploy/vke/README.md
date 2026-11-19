# Tatara runners on Kubernetes (VKE) — AWAI-3542

T2 runners for the coordinator, run as a StatefulSet on a Kubernetes cluster
(Vultr VKE `awai-tatara-ci`, nrt, x86_64). No Nix and no image registry. They add capacity
next to the Mac mini runners and take part in the same quorum.

| File | Role |
|---|---|
| `tatara-runner.yaml` | ConfigMap (boot script) and StatefulSet `tatara-runner` in namespace `tatara` |
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

It then runs `bb run runner.edn`.

## Set up

```sh
kubectl create namespace tatara
# Tailscale auth key (reusable, ephemeral), read from the password manager without printing it:
kubectl -n tatara create secret generic tailscale-auth --from-literal=TS_AUTHKEY="$(op read 'op://…/credential')"
# One identity per ordinal (bb keygen in packages/tatara-coordinator); add each DID to the coordinator :allow set:
kubectl -n tatara create secret generic tatara-runner-identity-0 --from-file=identity.edn=<file>
kubectl apply -f tatara-runner.yaml
kubectl -n tatara scale statefulset tatara-runner --replicas=<1..3>
```

The VKE node pool `tatara-runners` (vc2-4c-8gb) has the autoscaler set to 1–3 nodes. Each runner
requests 1.5 CPU and 3 GiB, so two fit on a node; a third replica brings up a second node.

## State on 2026-11-19

- Pod 0 joined the tailnet and polls the coordinator. It was refused with `runner-not-allowed` until its
  DID was added; after that the coordinator listed 6 runners.
- Taking part in a verdict is shown on the next commit after that change (see AWAI-3542).
- Replica count is set by hand. Scaling on the coordinator's queue (for example KEDA) is not done yet.
