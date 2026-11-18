# tatara-coordinator

Tatara の制御面。run を受け、replica を runner に lease し、artifact と verdict を CAS に受け、quorum で結果を確定し、GitHub に返す送信を outbox に積む。end-user アプリではなく fleet のサービスである（設計書 Q1、`docs/fleet-ci-20261118.md`）。

状態: **source**（AWAI-3407）。実機での運用、GitHub への送信（AWAI-3408）、Linux VM runner（AWAI-3409）は未実装。

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

`bb test`（bb 1.12.218、2026-11-18）: 5 tests、27 assertions、0 failures。

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
