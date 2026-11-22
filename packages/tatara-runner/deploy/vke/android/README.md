# Tatara APK lane on VKE (AWAI-3834)

Builds the Itonami series apps' debug APKs on the `awai-tatara-ci` cluster instead of the owner's Mac.
Artifacts go to B2 with a sha256 manifest and a read-back check. No signing key is on CI: the APKs are
signed with the Android debug keystore only. Production signing stays on the signing Mac.

| File | Role |
|---|---|
| `toolchain.tsv` | The pinned toolchain: base image digest, JDK, node, Rust parts, cmdline-tools (sha256 or Google's sha1), SDK packages by exact path and Google's sha1. Its sha256 is the toolchain id. |
| `install.sh` | Installs the TSV under `/cache/toolchain/<id>` on the cache volume, checks every archive first, then writes `manifest.lock` (sha256 tree digest per component). Every boot re-checks the tree digests. |
| `apk-runner.yaml` | Cache volume (60 Gi HDD, Retain), boot, and StatefulSet `tatara-runner-apk-0` (one DID, toolchain `:android-v1`) on the `awai-apk-burst` pool. |
| `apk-scaler.yaml` | Scaler for runs that need `:android-v1` only (0 or 1 replica, 600 s idle). |
| `burst.yaml` | In-cluster pool manager (`tatara.runner.burst`). Not applied until it has its own [vke]-only Vultr key. |
| `pool-local.sh` | Stopgap until then: create the pool from the operator's Mac, delete it at a deadline (default 60 min) or on exit, and verify it is gone. |

## Flow

1. A repo's trusted pipeline (its default branch's `.tatara/pipeline.edn`) asks for `:toolchain :android-v1`, `:quorum 1`, `:network :fetch`, declares the APK, `release.json` and `descriptor.json` as artifacts, and names its caches and `:publish`.
2. The coordinator queues the run. `GET /v1/queue?tier=t2&toolchain=android-v1` shows the demand.
3. The pool `awai-apk-burst` (vc2-4c-8gb, 1 node, taint `awai.network/apk=build:NoSchedule`, label `awai.network/reapable=true`) is created, and the APK scaler starts the runner. The pod selects the pool by `vke.vultr.com/node-pool`, so the build-reaper (AWAI-3664) counts it as activity.
4. The runner restores caches, runs the steps as uid 20000 through the egress proxy, uploads the artifacts to the coordinator's CAS and signs the verdict.
5. The coordinator's publisher uploads the artifacts and `manifest.json` to `b2://tatara-apk-artifacts/<repo>/<sha>/<run>/`, downloads each back and compares size and sha256, then posts the `tatara/apk` commit status with a 7-day link to the APK.
6. Without demand, the runner goes to 0 after 600 s and the pool is deleted after 900 s.

## Security

- Same hardening as phase 0 (AWAI-3799): own user namespace, no service account token, no DNS, egress only to the coordinator and the proxy allowlist, steps without capabilities and with `no_new_privs`, pipeline from the default branch.
- Registries the lane needs, all on the proxy allowlist (`../egress.yaml`): `dl.google.com`, `maven.google.com` (SDK, AGP), `repo.maven.apache.org`, `repo1.maven.org` (Maven Central), `services.gradle.org`, `downloads.gradle.org`, `plugins.gradle.org`, `plugins-artifacts.gradle.org` (Gradle wrapper and plugin portal), `registry.npmjs.org`, `index.crates.io`, `static.crates.io`, `static.rust-lang.org`, `nodejs.org`, `github.com` and its release hosts, and the Ubuntu archive.
- Caches: only a passed default-branch run writes an entry (`tatara.runner.cache`). PR runs read a copy. Steps cannot write the volume.
- B2: bucket `tatara-apk-artifacts` is private and has no lifecycle rules (every version is kept). The coordinator's key `tatara-coordinator-apk-publish` is scoped to that bucket with listFiles, readFiles, writeFiles and shareFiles, and no deleteFiles. It expires after 90 days. The runner never sees it.
- The Vultr key for the pool manager is a separate key on a [vke]-only sub-user (not the account key, not the reaper's key).

## Pinned toolchain lock

After the first install on the cluster, copy `/cache/toolchain/<id>/manifest.lock` to `toolchain.lock` here. A boot then also fails if an installed component's tree digest differs from the committed one.
