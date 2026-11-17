# app-tatara

Awai の分散 CI/CD アプリ。GitHub Actions に依存せず、複数の runner が同じ job を実行し、署名付き verdict の quorum で結果を確定する。Murakumo から独立したアプリで、Murakumo は差し替え可能な provider の一つ。

> **状態: 設計のみ。実装はまだない。** 構成は提案で、各 package は空の骨組み。

## 正本

- 設計書: [awai-os docs/otehon/ci-app-20261117.md](https://github.com/network-awai/awai-os/blob/main/docs/otehon/ci-app-20261117.md)(追跡: Linear AWAI-3016)
- 元になる ADR: `kotoba-lang/murakumo` ADR-0001(coordinator、lease、runner DID、verdict quorum、CAS、outbox、capability-based CD)
- 名前は owner 決定(2026-11-17)。未決の項目は設計書 9 章 Q1, Q2, Q4〜Q10。

## 構成(提案)

| path | 役割 |
|---|---|
| `apps/tatara` | UI(閲覧・承認)。静的配信のみ |
| `packages/tatara-core` | RunRequest / verdict / quorum / CD policy の純粋ロジック(`.cljc`) |
| `packages/tatara-coordinator` | broker・outbox。end-user app とは別ロールのサービス |
| `packages/provider-murakumo` | provider port の Murakumo 実装 |
| `packages/provider-awai-device` | Awai OS の Tauri / Android 端末での runner・承認 |

## 守る制約

- アプリの origin は静的配信のみ。リクエスト時のアプリ処理は持たない。
- 外部サービスへの操作は、操作ごとの新しい承認の後だけ行う。共有 runtime `kotoba.app.client-runtime.v1` を使い、承認ロジックを複製しない。
- 端末の無人 runner と同意契約の関係は未決(設計書 6.3、Q2)。決定までは実装しない。

## 次の作業(設計書 Phase P0)

`tatara-core` は P0 実装済み(packages/tatara-core/README.md)。次は P1(読み取り専用 viewer)。以下は P0 の受入だった内容。同一 DID 2 件で quorum 不成立、割れた verdict はハードエラー、CAS 欠落で完了拒否、ほか ADR-0001 の失敗モード各 1 ケース以上。
