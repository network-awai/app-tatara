#!/bin/sh
# Tatara T2 runner on a plain Linux x86_64 host, without Nix (AWAI-3542). Same pins as tatara-runner.yaml.
# Run as root on Ubuntu 24.04:  sh provision-host.sh <app-tatara commit>
# Installs pinned tools (version + digest checked), the tatara-runner source at a fixed commit,
# and a systemd unit. The runner config and identity are created separately (see README).
set -eu
TATARA_REV=${1:?app-tatara commit}
NODE_VERSION=22.23.5
NODE_SHA256=cdeb8670a0c81f51b4dc4ac61919f9b1ad917b304bf8221eb939570663d240f8   # node-v22.23.5-linux-x64.tar.xz (nodejs.org SHASUMS256)
BB_VERSION=1.12.218
BB_SHA256=7bd028cc794732ffde3da31ce4379840893c8e54f1046f92a8dfc4f4b3cddaf8     # babashka-1.12.218-linux-amd64-static.tar.gz (release .sha256)
NBB_SHA512=SvkQfK+wKKy10F7c4llVX/+ys0sKEHeFwLPIA3UFrD3LBjLKbkUv7huQcHviZ1rEZ/qffTHp9u2OioyekqVgOA==        # nbb-1.4.208.tgz (npm integrity, as in apex ci/otehon-nbb.nix)
RESOLVE_SHA512=Iqv2fzaTQN28s/FwZAoFq0ZSs/7hMAHJVX+w8PZl3cY19Pxk6jFFalxQoIfW2826i/fDLXv8IiEZRIT0lDuWcg==  # import-meta-resolve-4.2.0.tgz

export DEBIAN_FRONTEND=noninteractive
apt-get update -q >/dev/null
apt-get install -y -q git curl unzip xz-utils ca-certificates util-linux python3 >/dev/null
T=$(mktemp -d); cd "$T"
check256() { echo "$2  $1" | sha256sum -c - >/dev/null || { echo "digest mismatch: $1" >&2; exit 3; }; }
check512() { [ "$(openssl dgst -sha512 -binary "$1" | base64 -w0)" = "$2" ] || { echo "digest mismatch: $1" >&2; exit 3; }; }

curl -fsSLo node.tar.xz "https://nodejs.org/dist/v$NODE_VERSION/node-v$NODE_VERSION-linux-x64.tar.xz"; check256 node.tar.xz $NODE_SHA256
rm -rf /opt/node; mkdir -p /opt/node; tar -xJf node.tar.xz --strip-components=1 -C /opt/node
ln -sfn /opt/node/bin/node /usr/local/bin/node; ln -sfn /opt/node/bin/npm /usr/local/bin/npm; ln -sfn /opt/node/bin/npx /usr/local/bin/npx

curl -fsSLo bb.tgz "https://github.com/babashka/babashka/releases/download/v$BB_VERSION/babashka-$BB_VERSION-linux-amd64-static.tar.gz"; check256 bb.tgz $BB_SHA256
tar -xzf bb.tgz -C /usr/local/bin bb

curl -fsSLo nbb.tgz https://registry.npmjs.org/nbb/-/nbb-1.4.208.tgz; check512 nbb.tgz $NBB_SHA512
curl -fsSLo resolve.tgz https://registry.npmjs.org/import-meta-resolve/-/import-meta-resolve-4.2.0.tgz; check512 resolve.tgz $RESOLVE_SHA512
rm -rf /opt/nbb; mkdir -p /opt/nbb/node_modules/import-meta-resolve
tar -xzf nbb.tgz --strip-components=1 -C /opt/nbb; tar -xzf resolve.tgz --strip-components=1 -C /opt/nbb/node_modules/import-meta-resolve
printf '#!/bin/sh\nexec /opt/node/bin/node /opt/nbb/cli.js "$@"\n' > /usr/local/bin/nbb; chmod 755 /usr/local/bin/nbb

rm -rf /opt/tatara; git clone -q https://github.com/network-awai/app-tatara.git /opt/tatara
git -C /opt/tatara checkout -q --detach "$TATARA_REV"
mkdir -p /var/lib/tatara/work; chmod 700 /var/lib/tatara

cat > /etc/systemd/system/tatara-runner.service <<EOF
[Unit]
Description=Tatara T2 runner (AWAI-3542)
After=network-online.target tailscaled.service
Wants=network-online.target
ConditionPathExists=/var/lib/tatara/runner.edn

[Service]
WorkingDirectory=/opt/tatara/packages/tatara-runner
ExecStart=/usr/local/bin/bb run /var/lib/tatara/runner.edn
Environment=PATH=/usr/local/bin:/usr/bin:/bin
Restart=always
RestartSec=10
CPUQuota=350%
MemoryMax=7G

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
cd /; rm -rf "$T"
echo "tools: node $(node --version), bb $(bb --version), nbb $(nbb --version 2>/dev/null | head -1); tatara $(git -C /opt/tatara rev-parse --short HEAD)"
