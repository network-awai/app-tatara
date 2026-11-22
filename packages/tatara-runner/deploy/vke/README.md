# Tatara runners on Kubernetes (VKE) — AWAI-3542

T2 runners for the coordinator, run as StatefulSets on a Kubernetes cluster
(Vultr VKE `awai-tatara-ci`, nrt, x86_64), which also hosts the coordinator itself. No Nix and no image registry. They add capacity
next to the Mac mini runners and take part in the same quorum.

| File | Role |
|---|---|
| `tatara-runner.yaml` | Boot ConfigMap and one StatefulSet per runner identity (`tatara-runner-0..2`, replicas 0 or 1) |
| `scaler.yaml` | Queue-based scaler (Deployment `tatara-scaler`; RBAC limited to `statefulsets/scale` of `tatara-runner-0..2`) |
| `egress.yaml` | Egress proxy (tinyproxy) with the host allowlist; ClusterIP 10.96.79.128:3128 |
| `negative-control.sh` | Step sandbox probes, run in a live runner pod (see below) |
| `network-policy.yaml` | Default deny; what runner, proxy, coordinator and scaler pods may reach |
| `../../../tatara-coordinator/deploy/vke/coordinator.yaml` | The coordinator (system pool, 20 Gi Retain volume, ClusterIP 10.96.79.90:8790, tailscale sidecar) |
| `provision-host.sh` | Same pinned tools for a plain Linux x86_64 host with systemd, if a VM is used instead |

## How it works (hardened, AWAI-3799)

**Node pools.**
- `tatara-system` (vc2-2c-4gb, 1 node): system pods, the coordinator, the egress proxy and the scaler.
- The runner pool (`tatara.network/role=runner`, taint `tatara.network/runner=ci:NoSchedule`): vc2-1c-2gb, autoscaler 1–3. VKE node pools cannot scale up from 0: Vultr rejects a minimum of 0 on update, and the autoscaler has no template node for an empty pool. So the floor is one runner node.
- Runner pods tolerate only their own taint and select only `role=runner`. They do not land on the release build pool (`awai-pc-release-*`) or on `yataverse-acme`.

**Identity.**
- StatefulSet `tatara-runner-N` mounts only Secret `tatara-runner-identity-N` (mode 0400). A pod holds one DID.
- Required pod anti-affinity puts each runner on its own node.
- The coordinator runs with `:distinct-origins? true`: the replicas of a run must come from different source addresses, and a verdict must come from the address its lease went to.
- The identities were reissued on 2026-11-21. Before that, every pod mounted all three.

**Privileges.**
- Pods run with `hostUsers: false`. Root in the pod maps to an unprivileged uid range on the node.
- Seccomp is `RuntimeDefault`. There is no service account token and no DNS.
- Capabilities are dropped except CHOWN, DAC_OVERRIDE, FOWNER (for apt at boot), SETUID, SETGID and SYS_ADMIN, all scoped to the pod's user namespace. SYS_ADMIN is kept only because the RuntimeDefault seccomp profile allows `unshare` only to containers that hold it. There is no NET_ADMIN.
- Steps run as uid 20000 with no capabilities and `no_new_privs`, through `setpriv`. They cannot read the identity (0600, root).
- `:network :none` steps drop to uid 20000, then unshare their own user and network namespace (only loopback) and clear their bounding set.
- The root filesystem is not read-only: the boot installs packages, because there is no image registry (D2).

**Why the pod keeps SYS_ADMIN, and how it is checked.**
- SYS_ADMIN is in the container spec only because containerd's RuntimeDefault seccomp profile allows `unshare(2)` and namespace-creating `clone(2)` only to containers that hold it. Without it, every `:none` step failed with EPERM (2026-11-21).
- It is scoped to the pod's user namespace (root there is an unprivileged host uid range). The step itself runs with CapEff 0, CapBnd 0 and `no_new_privs`.
- A custom `Localhost` seccomp profile could drop it. That profile would be RuntimeDefault plus an unconditional allow of `unshare`, and of `clone`/`clone3` with `CLONE_NEWUSER|CLONE_NEWNET`.
- On VKE the profile file has to be on every node under `/var/lib/kubelet/seccomp/`. Getting it there needs a privileged DaemonSet with a hostPath or a custom node image. Either one adds more privilege than it removes, so it is not done.
- `negative-control.sh` runs probes as a step, built by the runner's own `wrap-network`. All 12 must pass: no capabilities, no `setns` into the pod's net, mnt or user namespace, identity and runner environment unreadable, no link creation, only loopback, no mount, and a nested user namespace cannot enter the pod netns.
- Run as the runner (outside the wrapper), the same probes report 7 failures. This shows they detect a broken sandbox.

**Network.**
- There is no tailscale, `NET_ADMIN` or `/dev/net/tun` on runner pods.
- A runner pod can reach only the coordinator and the egress proxy. It cannot reach the tailnet (100.64.0.0/10), RFC1918, node or metadata addresses, the API server, or DNS. A negative probe verified this on 2026-11-21.
- Boot downloads and `:fetch` steps use the proxy. The proxy forwards only to the allowlisted hosts in `egress.yaml`, and its own policy allows only public addresses.
- A runner refuses `:fetch` unless it has a `:fetch :proxy` configured.

**Pipelines.** The coordinator runs the default branch head's `.tatara/pipeline.edn` for every head, PR heads included. A PR cannot change its steps, `:network`, tier or toolchain.

**Scaling.**
- `tatara-scaler` polls `GET /v1/queue?tier=t2` every 30 s. The answer counts queued jobs plus live leases of `:t2` runs that need no toolchain.
- With demand, it sets the first n StatefulSets to 1 at once (n at most 3).
- With no demand for 600 s, it sets them all to 0. An unreachable coordinator counts as no demand.
- The node autoscaler follows the pods.

## Set up

```sh
kubectl create namespace tatara
# One identity per ordinal (bb keygen in packages/tatara-coordinator); add each DID to the coordinator :allow set:
kubectl -n tatara create secret generic tatara-runner-identity-0 --from-file=identity.edn=<file>
# Tailscale auth key, used by the coordinator pod only:
kubectl -n tatara create secret generic tailscale-auth --from-literal=TS_AUTHKEY="$(op read 'op://…/credential')"
# Coordinator config and secrets (not in Git): coordinator.edn with :root "/var/lib/tatara/state" :bind "0.0.0.0"
# :distinct-origins? true and lease identities under /secrets/:
kubectl -n tatara create configmap tatara-coordinator-config --from-file=coordinator.edn=<file>
kubectl -n tatara create secret generic tatara-coordinator-secrets --from-file=admin-token=<file> \
  --from-file=lease-identity.edn=<file> --from-file=lease-identity-apex-merge.edn=<file>
# Replace TATARA_REV_PLACEHOLDER with a commit on main, then:
kubectl apply -f network-policy.yaml -f egress.yaml -f tatara-runner.yaml -f scaler.yaml \
  -f ../../../tatara-coordinator/deploy/vke/coordinator.yaml \
  -f ../../../tatara-coordinator/deploy/vke/minter.yaml
```

Mac mini runners reach the coordinator over the tailnet at its address, `100.109.2.60:8790` on 2026-11-21.

## State on 2026-11-19

- Pod 0 joined the tailnet and polls the coordinator. Its DID was added to `:allow`.
- Its first verdict, on app-tatara main `3a5b11fe`, was `:failed` against the mini's `:passed` (split threshold).
  The cause was the missing `ip` command (above), fixed in #19.
- After #19, the scaler went from 0 to 1 replica within one poll of the run for `6c3b3aac`. The Mac minis had
  already taken both jobs before the pod finished booting (about 1–2 min).
