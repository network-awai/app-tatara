#!/bin/sh
# Install the runner inside the Lima VM. Run as root inside the VM:
#   limactl shell tatara-runner sudo sh /path/to/provision.sh BB_VERSION BB_SHA256
# Status: TEMPLATE, not run on the fleet yet (AWAI-3411).
set -eu
BB_VERSION="$1"; BB_SHA256="$2"
cd /tmp
curl -fsSLo bb.tgz "https://github.com/babashka/babashka/releases/download/v${BB_VERSION}/babashka-${BB_VERSION}-linux-aarch64-static.tar.gz"
echo "${BB_SHA256}  bb.tgz" | sha256sum -c -
tar -xzf bb.tgz -C /usr/local/bin bb
# Nix multi-user install; pin and verify the installer digest the same way
# before qualification. Steps reach Nix through NIX_REMOTE=daemon.
