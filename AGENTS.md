# app-tatara

Follow the workspace AGENTS.md. Design: `docs/fleet-ci-20261118.md` (fleet) and awai-os `docs/otehon/ci-app-20261117.md` (app).

## Fleet operations (Mac mini × 11)

- The macOS user on every mini is `node`. Reach a mini over the tailnet by IP, through Tailscale's `nc`, with a per-host key alias; this Mac does not resolve MagicDNS names:
  `ssh -o ProxyCommand="tailscale nc <tailnet-ip> 22" -o HostKeyAlias=ts-<host> node@<tailnet-ip>`.
  The old per-host user names in `~/.ssh/config` and murakumo `fleet.edn` do not work.
- Read before you change anything on a mini. Record what you observed, with time, in the Linear issue.
- Do not stop, reconfigure or resize the existing VMs (Awai OS, Awai Meiji Bot VM, Lane test) or jacob's Android emulator. They belong to other work.
- `sudo` needs a password on every mini. Do not try to bypass it; root-level steps (LaunchDaemons, `/etc/hosts`) need the owner.
- Canary first: asher, levi, naphtali. Expanding to the other minis needs the owner's decision on the measured impact (AWAI-3411 → AWAI-3412).
- Runners run unattended as fleet devices (owner decision 2026-11-18, design §1). Secrets (GitHub token, deploy credentials) stay in the coordinator, never on a mini.

## Code

- `packages/tatara-core`: pure `.cljc`. `bb test` there.
- `packages/tatara-coordinator`, `packages/tatara-runner`: Babashka. `bb test` in each. Owner decision 2026-11-18 allows nbb / bb / Node here.
- Each unit: Linear comment, commit naming the issue, push, PR, merge (workspace rules).
