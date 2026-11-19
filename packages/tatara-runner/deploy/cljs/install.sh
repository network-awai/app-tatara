#!/bin/sh
# Provision outside the job sandbox. An optional verified artifact cache
# (argument two) allows installation without network access.
set -eu
source_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
target=${1:-/opt/tatara-toolchains/cljs-jvm-v1}
cache=${2:-}
case "$target" in /*) ;; *) printf '%s\n' 'install target must be absolute' >&2; exit 2 ;; esac
case "$(uname -s):$(uname -m)" in
  Linux:aarch64)
    platform=linux; node_platform=linux-arm64
    jdk_sha=e3522e707fc7dbe2a72c30746b1e7068301a0213ecd213d4491f35a14eb55578
    node_sha=8bd3dc69eee8f3f226315a80d7d1d4faccf217a4c8fae500168ac2e38f3bdba3
    sha256() { sha256sum "$@"; }
    ;;
  Darwin:arm64)
    platform=mac; node_platform=darwin-arm64
    jdk_sha=bf036b35b071bfb2f54f2c0af8ee8d88afa95ee6eb3dce02c0f94cfc2524d04a
    node_sha=e7131995759b61a0170b646010d2ad35a5d37b4cb0d471f3e8317eac6f742f3d
    sha256() { shasum -a 256 "$@"; }
    ;;
  *) printf '%s\n' 'unsupported toolchain platform' >&2; exit 2 ;;
esac
if [ -e "$target" ]; then
  printf 'install target already exists: %s\n' "$target" >&2
  exit 2
fi
mkdir -p "$(dirname -- "$target")"
stage=$(mktemp -d "${target}.XXXXXX")
trap 'rm -rf -- "$stage"' EXIT
trap 'exit 1' HUP INT TERM
fetch() {
  if [ -n "$cache" ]; then cp "$cache/$2" "$stage/$2"
  else curl -fsSL --retry 2 --max-time 150 "$1" -o "$stage/$2"; fi
  printf '%s  %s\n' "$3" "$stage/$2" | sha256 -c -
}
node_archive="node-v22.23.5-$node_platform.tar.gz"
fetch "https://nodejs.org/dist/v22.23.5/$node_archive" "$node_archive" "$node_sha"
fetch "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_aarch64_${platform}_hotspot_21.0.13_9.tar.gz" "jdk-$platform.tar.gz" "$jdk_sha"
fetch 'https://github.com/clojure/brew-install/releases/download/1.12.5.1654/clojure-tools-1.12.5.1654.tar.gz' clojure-tools.tar.gz dc86cc56bc372fcef07bd87f44693eeb4b61cf5f44347883878364d03b5fb342
mkdir -p "$stage/jdk" "$stage/clojure" "$stage/node" "$stage/bin"
tar -xzf "$stage/$node_archive" --strip-components=1 -C "$stage/node"
tar -xzf "$stage/jdk-$platform.tar.gz" --strip-components=1 -C "$stage/jdk"
tar -xzf "$stage/clojure-tools.tar.gz" --strip-components=1 -C "$stage/clojure"
mkdir "$stage/clojure/libexec"
mv "$stage/clojure/"*.jar "$stage/clojure/libexec/"
sed 's@^install_dir=PREFIX$@install_dir="$(cd -- "$(dirname -- "$0")" \&\& pwd)"@' "$stage/clojure/clojure" > "$stage/clojure/cli"
mv "$stage/clojure/cli" "$stage/clojure/clojure"
chmod 755 "$stage/clojure/clojure"
install -m 755 "$source_dir/clojure" "$stage/bin/clojure"
install -m 755 "$source_dir/tatara-cljs-test" "$stage/tatara-cljs-test"
ln -s ../node/bin/node "$stage/bin/node"
ln -s ../node/bin/npm "$stage/bin/npm"
cp "$source_dir/maven.sha256" "$stage/maven.sha256"
while read -r hash jar; do
  mkdir -p "$(dirname -- "$stage/maven/$jar")"
  fetch "https://repo.maven.apache.org/maven2/$jar" "maven/$jar" "$hash"
done < "$stage/maven.sha256"
rm "$stage/$node_archive" "$stage/jdk-$platform.tar.gz" "$stage/clojure-tools.tar.gz"
chmod -R a+rX,go-w "$stage"
mv "$stage" "$target"
