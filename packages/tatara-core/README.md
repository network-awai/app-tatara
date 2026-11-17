# tatara-core

RunRequest 正規化、replica lease、verdict の CID 再計算と quorum、CAS 存在検査つきの completion 受理、outbox、CD の ref・bundle・capability 検査。純粋ロジックのみ(`.cljc`)。I/O、署名、実行は持たない。

状態: **P0・P1 のロジック実装済み(source)**。P1 の viewer UI と取得は未実装(下記)。受入の根拠は下のテストだけ。built candidate・実機・公開の確認はない。

## 実行

```bash
bb test
```

`bb`(Babashka)で手元の一回限りの確認として走らせる。CI の gate には入れていない。bb v1.12.218、2026-11-17 に 13 tests / 70 assertions、0 failures。

## 失敗モードとテストの対応(ADR-0001)

| ADR-0001 の失敗モード | テスト |
|---|---|
| 重複 forge イベント | `duplicate-events-collapse` |
| runner 消失・古い lease・他人の artifact の主張 | `lease-rules`、`completion-admission` |
| 1 runner で閾値を満たす | `one-runner-one-replica`、`quorum-counts-distinct-dids` |
| 2 runner の結果が割れる | `split-threshold-is-hard-error` |
| verdict 確定後に外部 API が失敗 | `outbox-retry-and-no-repeat` |
| PR / feature ref の誤デプロイ | `cd-ref-and-bundle-gate` |
| capability 失効、canary、部分昇格、rollback | `cd-capability-and-rollback`(計画と順序のみ。実行は deploy-target port) |
| 電源断中の状態公開 | **対象外**。atomic write と fsync は I/O で、tatara-coordinator の受入にする |

各ケースに正の対照を併置した。quorum の重複 DID 判定、1 runner 1 replica、CAS 検査を壊す変更でテストが落ちることを手元で確認した(2026-11-17)。

## 未確認・注意

- CID は `cid:sha256:<hex>` + 独自の canonical 文字列で、kotoba-git の CID 形式ではない。形式の統一は未確認。
- ハッシュ関数は ctx で注入する。ブラウザ向けの同期 SHA-256 は未選定。
- 署名検証(Identity port)と CAS 実体(CAS port)は注入関数。実装は provider 側。
- quorum は、distinct DID 数が閾値に達した時点で verdict が複数あれば多数決せず hard error にする(保守的な解釈。ADR は「割れた閾値は hard error」とのみ記す)。
- テストは `.clj`(bb)で、cljs での実行は未確認。

## P1: 端末内検証(`tatara.core.verify`)

取得済みの run bundle を端末内で検証する純粋関数 `verify-run`。サーバの主張を信用せず、request の run id、各 verdict の CID、許可リスト、署名(Identity port)、同梱 object の CID を再計算し、quorum を署名付き entry から再計算して主張と照合する。固定フィクスチャは通り、artifact・request・verdict・署名・主張 CID の 1 文字違いはすべて失敗し、同一 DID の水増しによる quorum の過大申告も検出する(`test/tatara/verify_test.clj`)。テストの署名は「signer + CID のハッシュ」という代用で、本物の署名方式ではない。

**P1 の受入のうち未達**:
- viewer UI(`apps/tatara`)と run 状態の取得は未実装。
- 「外部通信は宣言済み fetch のみ、拒否時に 0 リクエスト」は未検証。取得は共有 runtime `kotoba.app.client-runtime.v1` の broker を通す必要があるが、正本 `kotoba-client-runtime.js` を確認できていない。ブロッカーとして記録し、製品内に独自の同意ロジックは置かない。
- ブラウザでの実行には cljs ビルドと同期 SHA-256 の選定が必要で、未着手。
