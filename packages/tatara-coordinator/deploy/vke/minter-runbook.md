# minter 移設 runbook: 21mbp → VKE (AWAI-3820 item 1, 決定 D1: VKE)

coordinator は VKE にいるのに GitHub token だけが laptop (21mbp) の minter から来ている。
minter を coordinator の隣 (tatara-system pool) に移す手順。鍵と vault を扱う step
(1, 2, 7) と cluster への apply (3, 5) は owner が行う。repo 側の実装は済み:
FileKeystore ([app-kagitaba#13](https://github.com/network-awai/app-kagitaba/pull/13)) と
`minter.yaml` + NetworkPolicy (本 repo, 本 PR)。

前提: `kubectl` が `awai-tatara-ci` を向いていること。21mbp へは tailnet SSH が通ること。

## 0. 準備

- app-kagitaba の FileKeystore PR を merge し、その merge sha を `KAGITABA_REV` に控える。
- 21mbp で minter の起動方法を確認する (launchd plist / 手動)。 step 7 で止める。

## 1. device key の取出し (owner, 21mbp 上)

keychain の `minter-device-key-v1` (service `network.awai.kagitaba`) から 32 バイトを取出し、
0400 のファイルにする。バイナリなので `-w` の出力をそのまま使う:

```sh
security find-generic-password -s network.awai.kagitaba -a minter-device-key-v1 -w > /tmp/device-key
wc -c /tmp/device-key   # 32 であること。違う場合は止めて申告
chmod 400 /tmp/device-key
```

このファイルは vault を開く鍵そのもの。repo・chat・ログに貼らない。

## 2. Secret と Config の作成 (owner, 手元から)

```sh
kubectl -n tatara create secret generic kagitaba-minter-secrets --from-file=device-key=/tmp/device-key
shred -u /tmp/device-key  # macOS では rm -P /tmp/device-key
```

`minter.json` は 21mbp の稼働ファイルを写し、`listen` と `vault` だけ変える:

```json
{ "vault": "/var/lib/kagitaba/state/vault.json", "listen": "0.0.0.0:8795", "...": "残りは 21mbp の現物どおり (issuer_item, api, requesters)" }
```

```sh
kubectl -n tatara create configmap kagitaba-minter-config --from-file=minter.json=<file>
```

## 3. apply (owner)

```sh
kubectl apply -f network-policy.yaml -f packages/tatara-coordinator/deploy/vke/minter.yaml
kubectl -n tatara set env deploy/kagitaba-minter KAGITABA_REV=<step 0 の sha>
```

初回起動は rustup + `cargo build --release` を行う (数分)。vault 未配置の間は
pod が seeding 手順をログに出して待つ (readiness は通らない)。

## 4. vault の移送 (owner)

21mbp の vault.json (81 MB) を pod に入れる:

```sh
kubectl -n tatara cp <21mbp-vault.json> deploy/kagitaba-minter:/var/lib/kagitaba/state/vault.json
kubectl -n tatara delete pod -l app=kagitaba-minter   # 再起動で serve 開始
kubectl -n tatara logs -l app=kagitaba-minter --tail=5  # rev と rustc の表示を確認
```

## 5. 切替え (owner)

- `tatara-coordinator-config` の coordinator.edn の `:lease :url` を
  `http://10.96.79.91:8795` に変え、coordinator を rollout restart。
- `/v1/health` (8795) と実際の lease (token が降ってくること) を確認する。
- lease 時間が 204 s から in-cluster RTT に落ちたことを記録する (AWAI-3820 の証跡)。

## 6. 21mbp の後始末 (owner)

- 21mbp の minter を止める (step 0 で確認した方法で)。
- `~/.tatara/coordinator` (1.5 GB, AWAI-3820 item 5) とは別物。混ぜずに item 5 の判断を待つ。
- vault.json の当時の写しは owner の手元に残す (Retain volume が正本になる)。

## rollback

- coordinator.edn の `:lease :url` を 21mbp に戻し、coordinator を restart。
- VKE 側は `kubectl -n tatara delete -f minter.yaml` で消せる (PVC は Retain なので残る)。

## 残課題 (follow-up, 本移設の scope 外)

- vault 台帳の肥大 (81 MB)。prune 機構は未実装。移設後に別 issue で。
- rustup-init pin の更新 (上流が installer を回したら boot が loudly に失敗する。sha を取り直す)。
- toolchain 1.98.1 は本作業時点の検証版。上げる場合は app-kagitaba で先に test する。
