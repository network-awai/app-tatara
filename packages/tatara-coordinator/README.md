# tatara-coordinator

Tatara の制御面。run を受け、replica を runner に lease し、artifact と verdict を CAS に受け、quorum で結果を確定し、GitHub に返す送信を outbox に積む。end-user アプリではなく fleet のサービスである（設計書 Q1、`docs/fleet-ci-20261118.md`）。

状態: **source**（AWAI-3407、AWAI-3408）。実機での運用と Linux VM runner（AWAI-3409）は未実装。

## 実行

```bash
bb test                      # e2e と失敗モードの試験
bb keygen runner-a.edn       # runner の鍵を作る（既存ファイルは上書きしない）
bb serve coordinator.edn     # 起動。設定は src/tatara/coordinator/main.clj
```

## 構成

| namespace | 役割 |
|---|---|
| `service` | 状態遷移。tatara-core の `run` / `lease` / `verdict` / `outbox` を使う純粋関数 |
| `http` | HTTP（本文は EDN）。変更は永続化してから応答する |
| `store` | 状態ファイル（一時ファイルに書き、fsync、rename、ディレクトリの fsync）と CAS |
| `crypto` | SHA-256 と Ed25519 の runner 鍵 |
| `client` | runner 側の呼び出し（T5 の runner も使う） |
| `github` | GitHub の poller と commit status の送信（AWAI-3408） |

## GitHub 連携（AWAI-3408）

設定に `:github {:repos [...] :interval-ms 60000 :token-env "TATARA_GITHUB_TOKEN"}` を入れると、coordinator の中で 2 つの処理が回る。受信用の公開口（webhook）は作らない。token は coordinator の process の中だけで使い、runner には渡さない。

- **poller**: 各 repo の default branch と開いている PR の head を読む。その commit に `.tatara/pipeline.edn` があれば run を作る。一度見た commit は、その process の中では読み直さない。
- **送信**: outbox の commit status（context `tatara/ci`）を送る。GitHub が 2xx を返したときだけ送信済みにして永続化し、失敗したものは次の周回で再送する。

`bb test` の `github-test` は偽の GitHub API に対して、run の作成、pipeline のない commit の除外、再 poll で重複しないこと、500 の後の再送、quorum の結果が `success` で届くこと、送信済みが再起動後も残ることを試験する。負の対照: 2xx 以外でも送信済みにする変更で、再送の試験が失敗することを確認した。本物の GitHub API には読み取りだけを試した（2026-11-18、network-awai-apex の head 5 件を取得。pipeline はまだない）。実際の status の書き込みは、runner の配備（AWAI-3411）の後に行う。

## GitHub token の lease（AWAI-3459）

設定の `:github` に `:lease {:url "http://MINTER:8795" :identity "lease-identity.edn" :owner "network-awai" :repositories [...] :permissions {...}}` を入れると、coordinator は長期の GitHub token を持たず、kagitaba-minter から 1 時間以内の installation token を lease として受け取る（期限の 5 分前に取り直す）。要求は coordinator の Ed25519 identity で署名し、token は coordinator の X25519 鍵へ封入されて届く。`bb lease-keygen lease-identity.edn` で identity を作り、表示された DID と X25519 公開鍵を minter の方針に登録する。`:token-env` の固定 token は手元の試験用として残す。

`test/tatara/coordinator/lease_test.clj` は、kagitaba-minter の `lease_fixture` example が出力した fixture（試験用の鍵だけ）で、署名が Rust と一致すること、Rust が封じた lease を開けること、期限の改ざんを拒否すること、偽の minter に対する再利用と取り直し、拒否時の例外を確かめる。

## API

| 呼び出し | 認証 | 内容 |
|---|---|---|
| `POST /v1/runs` | 管理 token | `{:event :pipeline}`。pipeline のハッシュが `:pipeline-cid` と一致しなければ拒否。同じ commit と pipeline は同じ run |
| `POST /v1/lease` | runner 署名 | 対応する tier の、最も古い run の replica を渡す。1 つの DID は 1 run に 1 replica まで |
| `POST /v1/heartbeat` | 署名と lease token | lease を延長する |
| `PUT /v1/blob/<sha256>` | lease token | bytes を受け、sha256 を再計算してから保存する |
| `POST /v1/object` | 署名と lease token | 値を受け、CID を再計算して保存する。blob の記述子は blob が先に必要 |
| `POST /v1/complete` | 署名と lease token | verdict を `admit-completion` で受理し、quorum を計算する |
| `GET /v1/runs`、`/v1/runs/<id>`、`/v1/object/<cid>` | なし | 状態の読み出し（端末の検証用） |

runner の署名は、本文そのものに対する Ed25519 署名で、本文の `:ts` が ±120 秒以内であることも確かめる。runner DID は `did:tatara:ed25519:<base64url SPKI>` で、この repo だけの識別子である（did:key や kotoba-rad の形式ではない）。

## 試験と ADR-0001 の失敗モード

`bb test`（bb 1.12.218、2026-11-18）: 6 tests、39 assertions、0 failures（e2e と GitHub）。

| 失敗モード | 試験 |
|---|---|
| 重複イベント | 同じ commit と pipeline の再配送が同じ run になる |
| 1 runner で閾値を満たす | 同じ DID は 2 つ目の replica を受けられない（再起動の後も） |
| 古い・期限切れの lease | heartbeat が `:lease-expired`、別の runner に再割り当てされ、元の runner の完了は拒否 |
| 他人の artifact の主張 | 間違った token の blob・完了は 403 |
| CAS の欠落 | receipt がないと `:cas-missing` |
| 2 runner の結果が割れる | `:hard-error`（`:split-threshold`）と GitHub の `error` status |
| 許可外の runner、偽の署名、古い要求 | 403、401、401 |
| coordinator の再起動 | run、最初の verdict、使用済み DID が復元され、残りの runner で quorum が成立する |

負の対照: 状態の永続化を外すと再起動の試験が、署名の検証を外すと偽の署名の試験が失敗することを確認した。

電源断の試験（OS ごと落ちる）、複数 coordinator、GitHub への実送信は対象外。

## 常駐（macOS launchd、AWAI-3521）

`deploy/launchd/` の `run-coordinator.sh` を `~/.tatara/coordinator/` に置き、`network.awai.tatara.coordinator.plist` の `@HOME@` を置き換えて `~/Library/LaunchAgents/` から `launchctl bootstrap gui/$(id -u) …` で読み込む。

- admin token は `~/.tatara/coordinator/admin-token`（mode 600）から wrapper が読む。600 でなければ起動しない（exit 78）。plist に秘密は入れない
- GitHub token は渡さない（`TATARA_GITHUB_TOKEN`、`GITHUB_TOKEN`、`GH_TOKEN` を消してから起動）。`coordinator.edn` の `:github :lease` で kagitaba-minter から 1 時間の token を受け取る
- `KeepAlive` で異常終了しても再起動する
