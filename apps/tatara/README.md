# tatara（fleet 機器の Android アプリ）

状態: **T6 の最初の段階**（AWAI-3410、2026-11-19）。AOSP arm64 エミュレータ（API 35）で起動し、run を端末の中で検証できる。Wasm ジョブ（T1）と、同じ mini の VM runner の監督はまだない。

Tauri v3 アプリ。owner 決定（`docs/fleet-ci-20261118.md` §1 の 3）により、mini 上の Android は fleet の機器として無人で動く。利用者の電話向けの有人 runner とは別の形。

## できること

| 機能 | 実装 |
|---|---|
| 端末の runner DID | host（Rust）が Ed25519 の鍵をアプリ専用の領域に 0600 のファイルで作り、DID だけを画面に渡す。Keystore への移行は後の段階（設計 §9） |
| run の一覧 | host が設定された coordinator に `GET /v1/runs` を送る。画面は外部に通信しない |
| 端末での検証 | host が `GET /v1/runs/<id>/bundle` を取り、画面が **tatara-core の `verify-run` そのもの**（`packages/tatara-core` の `.cljc` を build 時にコピー）を scittle で実行する。CID、Ed25519 の署名、allowlist、quorum を計算し直し、coordinator の主張と比べる |
| 固定の run | `test/fixtures/run-bundle.edn`（本物の coordinator が出した run）と、署名・結果・commit・artifact・主張・allowlist の改ざん版を画面で検証できる |

秘密（GitHub の token、デプロイの資格）は端末に置かない。coordinator へは読み取りの GET だけを送る。

## 検証の仕組み

- SHA-256 と Ed25519 は `@noble/hashes`、`@noble/curves`（`src/crypto.js`、esbuild で 1 file に束ねる）。tatara-core の `cid` は同期の hash を要するため、WebCrypto ではなくこれを使う。署名の検証は RFC 8032 の厳格な形（`zip215: false`）
- 正準形の一致: fixture の artifact 名に非 ASCII、引用符、改行、tab、backslash を入れ、coordinator（Babashka）と画面（scittle / nbb）で同じ CID になることを確かめる

## 作る・試す

```bash
npm install
npm test            # dist を作り、nbb で verify を試す（固定の run が通り、改ざん版が全部落ちる）
npm run build:android
```

fixture を作り直す（coordinator が oracle）:

```bash
bb --classpath ../../packages/tatara-coordinator/src:../../packages/tatara-core/src test/make_fixture.clj
```

host の試験は `cd src-tauri && cargo test`。`ANDROID_HOME`、`NDK_HOME`、`JAVA_HOME`（21）が要る。

`npm test` は npm の依存を使うので、`.tatara/pipeline.edn`（VM の中、ネットワークなし）には入れていない。

## まだないこと

- 決定的な Wasm ジョブ（T1）の実行と、coordinator からの T1 の lease
- 同じ mini の Linux VM runner の監督（生存、実行中の job）。今は run の replica を取った runner を表示するだけ
- 端末鍵の Android Keystore への移行（Ed25519 の Keystore 対応を確かめてから）
- 端末の DID を coordinator の allowlist に入れる手順の自動化（今は owner が `:allow` に足す）
