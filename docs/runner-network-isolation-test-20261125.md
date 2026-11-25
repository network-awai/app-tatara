# runner のネットワーク隔離の拒否試験（手順書・未実行）

- 状態: **手順書のみ**（2026-11-25 に作成。実行はしていない。実機の結果はまだない）。
- 対象: `tatara-runner` が `:network :none` の job を、ネットワーク隔離（`unshare --net`）の使えない環境では受けないこと。背景は `packages/tatara-runner/src/tatara/runner/core.clj` の `wrap-network` と `preflight-network`。
- 前提の設計: `docs/fleet-ci-20261118.md` §3・§6。runner は mini の Linux VM（Lima）の中で動く。VM を起動する試験は、owner の承認（AWAI-3411 → AWAI-3412）の後に行う。

## 1. 目的

`:network :none` の step は、`unshare --net --map-root-user` で新しい network namespace の中でのみ走るはずだ。この試験は、step の利用者（job のコード）が次へ出られないことを確認する。

1. LAN（mini の有線 LAN。`192.168.1.0/24` 側）
2. tailnet（Tailscale の `100.x` 側。VM のホスト mini や他 mini の tailnet アドレス）
3. ホスト（VM の外。Lima VM のホスト mini と、VM のデフォルトゲートウェイ経由の外）
4. クラウドのメタデータ（`169.254.169.254`、`fd00:ec2::254`、`metadata.google.internal` など。Lima では通常届かないが、将来の移行先で漏れる経路を塞ぐ）
5. job の利用者から `sudo` を奪った状態（step-user に落とした後も、特権を取り戻せないこと）

なお、loopback だけは使える（step 内の test server 用。`wrap-network` が namespace 内で `ip link set lo up` する）。

## 2. 前提条件

- 専用の canary の VM（既存の Awai OS VM・Awai Meiji Bot VM・Lane test・Android エミュレータには触らない）。
- runner の設定は `deploy/runner.example.edn` を基に、`:network-enforcement :unshare` を入れる。`:step-user` を使う構成では `{:uid 20000 :gid 20000}` 相当。
- 秘密（GitHub token、deploy 資格）は coordinator 側だけに置く。試験用の job には秘密を入れない。
- 時刻と mini 名を記録する。実機の観測は Linear の issue に記録する（repo の AGENTS.md）。

## 3. 起動時 preflight の確認（先にこれ）

1. `unshare` が使える VM で runner を起動する。起動直後に probe（`unshare --net --map-root-user -- sh -c "ip link set lo up && exec \"$@\"" tatara-step true` 相当）が 1 回だけ走り、成功して lease の loop に入ること。
2. `unshare` が `EPERM` になる環境（Lima の VM で起こり得る）で起動する。明示的な理由（`network-enforcement :unshare is unavailable on this host; no jobs will be accepted`、probe の argv・exit・stderr 付き）で終了し、lease を取らないこと。coordinator 側に heartbeat が来ないことも確認する。
3. `:network-enforcement` を設定しない構成では、従来どおり起動すること（probe を実行しない）。

## 4. 隔離の拒否試験（job として実行）

試験用の pipeline（`:network :none`）の step で、次を試す。期待はすべて「接続できない / 失敗する」。step は argv のベクタだけ（shell 文字列は受け付けない）。

| # | 宛先 | 手順（例） | 期待 |
|---|---|---|---|
| 1 | LAN の mini | `curl` / `nc` で `192.168.1.1`（NAT ルーター）と canary の mini の LAN アドレスの公開ポートへ接続 | 超時・到達不能（namespace に NIC がない） |
| 2 | tailnet | 同 mini の tailnet `100.x` アドレスと、他の canary mini の `100.x` アドレスへ接続 | 超時・到達不能 |
| 3 | ホスト / 外 | `1.1.1.1` など外部 IP へ ping / TCP 接続 | 超時・到達不能。VM のデフォルトゲートウェイへも届かない |
| 4 | メタデータ | `169.254.169.254`（TCP 80）と `fd00:ec2::254` へ HTTP 接続 | 超時・到達不能。応答が返ったら**失敗**（漏洩） |
| 5 | loopback（対照） | step 内で一時サーバを立て、127.0.0.1 へ接続 | 成功すること（隔離が loopback を壊していない対照） |
| 6 | sudo 奪取 | `:step-user` 構成で step から `sudo -n true`、`setpriv` で root 化、`unshare -r` の再試行を試す | すべて失敗すること。capabilities が空で `no_new_privs` であること（`cat /proc/self/status` の `NoNewPrivs: 1`、`CapEff: 0000000000000000`） |

各 step の結果は artifact ではなく、receipt（step の exit）と log（log の sha256）に残る。step が非 0 終了で止まる形にし、verdict が `failed` になることまで確認する。

## 5. 記録の形式

- mini 名、VM の Lima 設定の digest、runner のコミット、`unshare` の版、probe の argv と exit・stderr。
- 上の表の各 # について：使ったコマンド、期待、実測、合否。
- 5 と 6 は対照（壊れてはいけないもの）なので、失敗したら隔離の有無とは別に「隔離が正しくない」として扱う。

## 6. 注意

- この文書は手順書であり、実機での実行結果ではない。実行時は AWAI-3411（canary）の承認を経てから行い、結果を Linear に記録する。
- 既存 VM の停止・再設定・リサイズはしない。`sudo` が要る操作は owner に依頼する。
