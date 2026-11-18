# tatara-runner

T2 tier の runner（各 Mac mini の Linux VM の中で動く）。設計: `docs/fleet-ci-20261118.md` の §3 と S4。

状態: **source**（AWAI-3409）。VM での実行と fleet での資格は未確認（AWAI-3411）。

## 流れ

1. coordinator から lease を取る（runner の Ed25519 署名）。
2. source の snapshot（coordinator が GitHub から取得した tarball）を、lease で認証して coordinator から受け取る。sha256 を確かめる。GitHub の token は runner に渡らない。
3. 展開した tree の `.tatara/pipeline.edn` が、lease された pipeline CID と一致するかを確かめる。一致しなければ実行しない。
4. step を argv のまま順に実行する。最初の非 0 終了か、`:timeout-seconds` で止める。`:network :none` の step は、`:network-enforcement :unshare` のとき新しい network namespace（`unshare --net`）で実行する。
5. 宣言された artifact だけをホストが読み、blob と記述子を CAS に上げる。
6. verdict（source、pipeline、結果、artifact の CID だけ）と、receipt（各 step の exit・時間、log の sha256、使ったネットワークの強制方法）、snapshot を上げ、署名して完了する。

## 実行

```bash
bb test                 # in-process の coordinator と 2 つの runner で実際の pipeline を実行
bb run runner.edn       # daemon。設定例は deploy/runner.example.edn
```

## 試験（bb 1.12.218、2026-11-18、macOS）

5 tests、18 assertions、0 failures。

- 別 DID の 2 runner が同じ pipeline（`bb -e ...` で artifact を作る）を実行し、quorum が `passed` で成立する。作業ディレクトリは提出後に消える。
- 失敗する step は、両 runner とも `failed` の verdict になり、後続の step は実行されない。
- `:timeout-seconds` で step が止まり、`failed` になる。
- checkout した pipeline が lease と違うと、実行も提出もしない。負の対照: この照合を外すと試験が失敗する。
- 検証規則（tier、argv、ネットワーク、artifact のパス、pipeline CID）。

macOS では `unshare` がないため、試験は `:network-enforcement :none` で走らせている。強制方法は receipt に残る。Linux VM での `unshare` の実行は未確認。

## 配備の雛形（未検証）

| ファイル | 内容 |
|---|---|
| `deploy/lima/tatara-runner.yaml` | Lima（vz、arm64、4 vCPU、6 GiB、80 GiB、host のマウントなし） |
| `deploy/lima/provision.sh` | VM 内に Babashka を入れる（版と sha256 を引数で固定）。Nix は同じ方式で固定してから入れる |
| `deploy/launchd/network.awai.tatara.runner-vm.plist` | mini の起動時に VM を立ち上げ、落ちたら再起動する |
| `deploy/runner.example.edn` | runner の設定例 |

VM のイメージと Nix の installer の digest は、まだ固定していない。fleet で資格を取るとき（AWAI-3411）に固定し、それまでは未検証として扱う。
