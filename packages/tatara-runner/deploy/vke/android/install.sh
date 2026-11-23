#!/bin/sh
# Install (or check) the pinned Android builder toolchain under $CACHE/toolchain/<id> (AWAI-3834).
#   install.sh TOOLCHAIN.tsv CACHE PROXY [PINNED.lock]
# <id> is the sha256 of TOOLCHAIN.tsv, so a changed pin builds a new directory and never edits an old one.
# Archives are checked against the TSV before they are unpacked; SDK packages are installed by the pinned
# sdkmanager, which checks Google's sha1. After the install, the sha256 tree digest of every component is
# written to manifest.lock; every later boot recomputes and compares them, and so does PINNED.lock when given.
# Runs as the runner (root in the pod's user namespace). Steps run as uid 20000 and get read-only access.
# Prints one line: "toolchain <id> <state>" with state installed|verified.
set -eu
tsv=$1 cache=$2 proxy=$3 pinned=${4:-}
id=$(sha256sum "$tsv" | cut -c1-64)
dir="$cache/toolchain/$id"
export http_proxy="$proxy" https_proxy="$proxy"
phost=${proxy#http://}; phost=${phost%%:*}; pport=${proxy##*:}

tree() { # sha256 of a directory: every file's sha256 and every symlink target, sorted by path
  ( cd "$1" && { find . -type f -print0 | sort -z | xargs -0 -r sha256sum
                 find . -type l -printf '%p -> %l\n' | sort; } | sha256sum | cut -c1-64 )
}
components() { grep -v '^#' "$tsv" | awk -F'\t' 'NF>=6 && $3!="oci" {print $1}'; }
where() { # install path of component $1, relative to $dir
  case "$1" in
    jdk) echo jdk ;; node) echo node ;; cmake) echo cmake ;;
    rustc|cargo|rust-std-*) echo "rust/.parts/$1" ;;
    cmdline-tools) echo sdk/cmdline-tools/latest ;;
    *) grep -v '^#' "$tsv" | awk -F'\t' -v n="$1" '$1==n {print $4}' | sed 's|;|/|g; s|^|sdk/|' ;;
  esac
}
check() { # file algo digest
  case "$2" in
    sha256) echo "$3  $1" | sha256sum -c - >/dev/null ;;
    sha1) echo "$3  $1" | sha1sum -c - >/dev/null ;;
    *) false ;;
  esac || { echo "digest mismatch: $1 ($2)" >&2; exit 3; }
}
verify() { # dir lockfile
  while read -r name digest; do
    case "$name" in '#'*|'') continue ;; esac
    got=$(tree "$1/$(where "$name")")
    [ "$got" = "$digest" ] || { echo "toolchain $id: $name tree $got, want $digest" >&2; return 1; }
  done < "$2"
}

if [ -f "$dir/manifest.lock" ]; then
  verify "$dir" "$dir/manifest.lock"
  [ -z "$pinned" ] || verify "$dir" "$pinned"
  echo "toolchain $id verified"; exit 0
fi

mkdir -p "$cache/toolchain"
tmp="$dir.tmp-$$"; rm -rf "$tmp"; mkdir -p "$tmp/dl"
grep -v '^#' "$tsv" | while IFS="$(printf '\t')" read -r name version kind src algo digest; do
  [ -n "$name" ] || continue
  dest="$tmp/$(where "$name")"
  case "$kind" in
    oci) continue ;;  # the pod's image, pinned by digest in the StatefulSet
    tar|txz|zip)
      f="$tmp/dl/$name"
      curl -fsSL --retry 3 -o "$f" "$src"; check "$f" "$algo" "$digest"
      mkdir -p "$dest"
      case "$kind" in
        tar) tar -xzf "$f" --strip-components=1 -C "$dest" ;;
        txz) tar -xJf "$f" --strip-components=1 -C "$dest" ;;
        zip) u="$tmp/dl/$name.d"; mkdir -p "$u"; unzip -q "$f" -d "$u"; cp -a "$u"/*/. "$dest"/; rm -rf "$u" ;;
      esac
      rm -f "$f" ;;
    sdk) ;;  # below, once cmdline-tools and the JDK are in place
    *) echo "unknown kind $kind for $name" >&2; exit 2 ;;
  esac
done
# Rust: one sysroot from the pinned dist parts (no rustup).
for p in rustc cargo rust-std-x86_64 rust-std-aarch64-android; do
  sh "$tmp/rust/.parts/$p/install.sh" --prefix="$tmp/rust" --disable-ldconfig >/dev/null
done
# SDK packages, exact paths from the TSV. Licences are accepted as Android Studio does on first run.
sdk_paths=$(grep -v '^#' "$tsv" | awk -F'\t' '$3=="sdk" {print $4}')
export JAVA_HOME="$tmp/jdk"
sm="$tmp/sdk/cmdline-tools/latest/bin/sdkmanager"
popt="--proxy=http --proxy_host=$phost --proxy_port=$pport"
yes | "$sm" --sdk_root="$tmp/sdk" $popt --licenses >/dev/null
# shellcheck disable=SC2086
"$sm" --sdk_root="$tmp/sdk" $popt --install $sdk_paths >/dev/null
for p in $sdk_paths; do [ -d "$tmp/sdk/$(echo "$p" | tr ';' /)" ] || { echo "sdkmanager did not install $p" >&2; exit 4; }; done
rm -rf "$tmp/dl" "$tmp/sdk/.temp" "$tmp/sdk/.downloadIntermediates"
{ echo "# toolchain $id: sha256 tree digests (tatara-runner/deploy/vke/android/install.sh)"
  for name in $(components); do echo "$name $(tree "$tmp/$(where "$name")")"; done; } > "$tmp/manifest.lock"
[ -z "$pinned" ] || verify "$tmp" "$pinned"
cp "$tsv" "$tmp/toolchain.tsv"
chmod -R go-w "$tmp"; chmod -R a+rX "$tmp"
mv "$tmp" "$dir"
# Older toolchains and interrupted installs are not used again: drop them (the volume is 60 Gi).
for old in "$cache"/toolchain/*; do
  [ "$old" = "$dir" ] || rm -rf "$old"
done
echo "toolchain $id installed"
