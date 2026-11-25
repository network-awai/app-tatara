# runner のネットワーク隔離の拒否試験（手順書・未実行）

- 状態: **手順書のみ**（2026-11-25 に作成。実行はしていない。実機の結果はまだない）。
- 対象: `tatara-runner` が `:network :none` の job を、ネットワーク隔離（`unshare --net`）の使えない環境では受けないこと。背景は `packages/tatara-runner/src/tatara/runner/core.clj` の `wrap-network` と `preflight-network`。
- 前提の設計: `docs/fleet-ci-20261118.md` §3・§6。runner は mini の Linux VM（Lima）の中で動く。VM を起動する試験は、owner の承認（AWAI-3411 → AWAI-3412）の後に行う。

## 1. 目的

`:network :none` の step は、`unshare --net --map-root-user` で新しい network namespace の中でのみ走るはずだ。この試験は、step の利用者（job のコード）が次へ出られないことを確認する。

repo は公開なので、実際のアドレスはこの文書に書かない。宛先はすべて実行時に VM のホストの経路表から拾う。対象はホストの経路表にある全ての private 範囲（`10.0.0.0/8`・`172.16.0.0/12`・`192.168.0.0/16`・`100.64.0.0/10`）と、その先の外部である。

1. LAN（mini の有線 LAN。上の private 範囲のうち、実行時にホストの経路表で mini の有線 LAN 側として見えた範囲）
2. tailnet（Tailscale。上の private 範囲のうち、実行時にホストの経路表で tailnet 側として見えた範囲。VM のホスト mini や他 mini のアドレスは実行時に拾う）
3. ホスト（VM の外。Lima VM のホスト mini と、VM のデフォルトゲートウェイ経由の外部）
4. クラウドのメタデータ（リンクローカルのメタデータ endpoint。標準の endpoint 名とアドレスは実行時に決め、fleet の実アドレスは書かない。Lima では通常届かないが、将来の移行先で漏れる経路を塞ぐ）
5. loopback（対照。step 内の test server 用。`wrap-network` が namespace 内で `ip link set lo up` する）
6. job の利用者から `sudo` を奪った状態（`:step-user` に落とした後も、特権を取り戻せないこと）
7. `:step-user` を使わない VM の構成（`deploy/runner.example.edn`）で、job の利用者に passwordless sudo がある場合（奪ってから runner を動かす。前提条件。§2）
8. `:network :fetch` の step（`unshare` を使わない構成。LAN・tailnet・ホストに届かないこと）

なお、loopback だけは使える（step 内の test server 用。`wrap-network` が namespace 内で `ip link set lo up` する）。

## 2. 前提条件

- 専用の canary の VM（既存の Awai OS VM・Awai Meiji Bot VM・Lane test・Android エミュレータには触らない）。
- runner の設定は `deploy/runner.example.edn` を基に、`:network-enforcement :unshare` を入れる。`:step-user` を使う構成では `{:uid 20000 :gid 20000}` 相当。
- job の利用者（`:step-user` を使わない VM の構成では runner 自身のユーザー）に passwordless sudo がないこと。ある場合は、runner を動かす前に sudo を奪う（`/etc/sudoers.d/` から外す等）。奪えなければ**試験を始めない**（前提条件。sudo があれば job のコードが特権を取り戻して namespace を抜けられる）。奪った後は `sudo -n true` が失敗することを確認してから始める。sudo の変更が owner の作業になる場合は owner に依頼する。
- 秘密（GitHub token、deploy 資格）は coordinator 側だけに置く。試験用の job には秘密を入れない。
- 時刻と mini 名を記録する。実機の観測は Linear の issue に記録する（repo の AGENTS.md）。

## 3. 起動時 preflight の確認（先にこれ）

1. `unshare` が使える VM で runner を起動する。起動直後に probe（`unshare --net --map-root-user -- sh -c "ip link set lo up && exec \"$@\"" tatara-step true` 相当）が 1 回だけ走り、成功して lease の loop に入ること。
2. `unshare` が `EPERM` になる環境（Lima の VM で起こり得る）で起動する。明示的な理由（`network-enforcement :unshare is unavailable on this host; no jobs will be accepted`、probe の argv・exit・stderr 付き）で終了し、lease を取らないこと。coordinator 側に heartbeat が来ないことも確認する。
3. `:network-enforcement` を設定しない構成では、従来どおり起動すること（probe を実行しない）。

## 3.1 preflight が守らない範囲

`preflight-network` は、`:network-enforcement :unshare` の構成で `:network :none` の step が使う `unshare` の形だけを試す。次は**守らない**。この試験でも別に確かめる（§4 の #8 が :fetch、#1〜#4 が guest の内側）。

- `:network :fetch` の step: `unshare` を使わない（egress proxy 経由）。preflight は何も試さない。`:fetch` から LAN・tailnet・ホストに届かないことは、§4 #8 で確かめる（proxy 経由の取得は対照として成功すること）。
- `:network-enforcement` を設定しない構成: preflight は no-op で、job は隔離なしで動く。この試験の対象外（起動は従来どおり通ることだけ §3-3 で確かめる）。
- guest の外（ホスト側の pf）: mini の macOS 側 firewall 等は preflight の範囲外。この試験は step の利用者から見た guest の内側だけを確かめる。ホスト側の制御は owner の作業であり、別途確かめる。

## 4. 隔離の拒否試験（job として実行）

試験用の pipeline（`:network :none`）の step で、次を試す。期待はすべて「接続できない / 失敗する」。step は argv のベクタだけ（shell 文字列は受け付けない）。

| # | 宛先 | 手順（例） | 期待 |
|---|---|---|---|
| 1 | LAN | 実行時にホストの経路表から拾った全ての private 範囲（`10.0.0.0/8`・`172.16.0.0/12`・`192.168.0.0/16`・`100.64.0.0/10`）のうち LAN 側の範囲のゲートウェイとホストへ `curl` / `nc` で接続 | 超時・到達不能（namespace に NIC がない） |
| 2 | tailnet | 実行時にホストの経路表から拾った tailnet 側の範囲のホスト（VM のホスト mini や他 mini）へ接続 | 超時・到達不能 |
| 3 | ホスト / 外 | 実行時にホストの経路表から決めた VM のデフォルトゲートウェイと、その先の外部へ ping / TCP 接続 | 超時・到達不能 |
| 4 | メタデータ | 実行時に決めた標準のリンクローカル・メタデータ endpoint（TCP 80）へ HTTP 接続 | 超時・到達不能。応答が返ったら**失敗**（漏洩） |

| 5 | loopback（対照） | step 内で一時サーバを立て、127.0.0.1 へ接続 | 成功すること（隔離が loopback を壊していない対照） |
| 6 | sudo 奪取 | `:step-user` 構成で step から `sudo -n true`、`setpriv` で root 化、`unshare -r` の再試行を試す | すべて失敗すること。capabilities が空で `no_new_privs` であること（`cat /proc/self/status` の `NoNewPrivs: 1`、`CapEff: 0000000000000000`） |
| 7 | sudo 奪取（VM 構成） | `:step-user` を使わない構成（`deploy/runner.example.edn`）で、§2 の前提（passwordless sudo を奪った状態）から step で `sudo -n true`、`setpriv` で root 化、`unshare -r` の再試行を試す | すべて失敗すること。sudo が残っていたらこの試験を始めない（§2） |
| 8 | `:fetch` の step | `:network :fetch` の pipeline の step から、#1・#2 と同じ private 範囲へ直接接続を試す。対照として、proxy 経由の取得（`fetch-env` の proxy）が成功することも確かめる | 直接接続はすべて到達不能。proxy 経由は成功すること（対照） |

各 step の結果は artifact ではなく、receipt（step の exit）と log（log の sha256）に残る。step が非 0 終了で止まる形にし、verdict が `failed` になることまで確認する。

## 5. 記録の形式

- mini 名、VM の Lima 設定の digest、runner のコミット、`unshare` の版、probe の argv と exit・stderr。
- 上の表の各 # について：使ったコマンド、期待、実測、合否。
- 5 と 8 の対照（壊れてはいけないもの）が失敗したら、隔離の有無とは別に「隔離が正しくない」として扱う。

## 6. 注意

- この文書は手順書であり、実機での実行結果ではない。実行時は AWAI-3411（canary）の承認を経てから行い、結果を Linear に記録する。
- 既存 VM の停止・再設定・リサイズはしない。`sudo` が要る操作は owner に依頼する。
