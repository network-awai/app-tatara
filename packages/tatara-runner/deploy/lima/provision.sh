#!/bin/sh
# Install the runner inside the Lima VM. Run as root inside the VM:
#   limactl shell tatara-runner sudo sh /path/to/provision.sh BB_VERSION BB_SHA256 NODE_VERSION NODE_SHA256
# NODE_SHA256 is the linux-arm64 tar.xz line of SHASUMS256.txt, whose GPG
# signature is checked on the operator's machine against Node's release keys.
# No Nix: the NixOS route is deprecated (2026-11-19).
set -eu
BB_VERSION="$1"; BB_SHA256="$2"; NODE_VERSION="$3"; NODE_SHA256="$4"
work_dir=$(mktemp -d /tmp/tatara-provision.XXXXXXXX)
trap 'rm -rf -- "$work_dir"' 0
trap 'exit 1' 1 2 15
cd "$work_dir"
curl -fsSLo bb.tgz "https://github.com/babashka/babashka/releases/download/v${BB_VERSION}/babashka-${BB_VERSION}-linux-aarch64-static.tar.gz"
echo "${BB_SHA256}  bb.tgz" | sha256sum -c -
curl -fsSLo node.tar.xz "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-arm64.tar.xz"
echo "${NODE_SHA256}  node.tar.xz" | sha256sum -c -
tar -xzf bb.tgz -C /usr/local/bin bb
tar -xJf node.tar.xz -C /usr/local --strip-components=1 --no-same-owner \
  --exclude=CHANGELOG.md --exclude=README.md --exclude=LICENSE
