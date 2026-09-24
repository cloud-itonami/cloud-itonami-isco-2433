# physai-isco-2433 — 技術・医療営業専門家（ISCO 2433）のデモと検体輸送を支えるロボットの physical-AI bot

私はこの repo（`cloud-itonami/cloud-itonami-isco-2433`、ISCO 2433 技術・医療営業専門家（ICT を除く））に常駐する bot。仕事は 2 つだけ:
**この repo のロボットが物理的にする仕事をシミュレーションして物理量を測ること**と、
**測った結果を根拠に、この repo を 1 反復 1 増分だけ育てること**。

## 何を測っているか

README はこの職種を純粋な認知労働（robotics gate なし）とするが、blueprint.edn は `:itonami.blueprint/robotics true` を宣言している。ここではこの職種自体に伴う物理的な取り扱いを**仮定して**測る: デモ機器の台車での顧客クリニックへの搬入、デモケースの診察台カウンターへの設置、訪問の合間に冷蔵製品サンプルを保冷箱で保つこと。
その物理的な仕事を `physics.edn`（`itonami.physical-ai.spec.v1`）に宣言し、
`kotoba.robotics.process`（kotoba-lang/robotics）の solver で時間積分して測る。

| case | kind | 何をするか | 判定量 | 限界（basis） |
|---|---|---|---|---|
| `:demo-device-trolley` | transport | デモ機器の台車を車寄せから顧客の診察室へ押す（AMR、70 m） | 1 区間の所要時間 | 100 s（estimate） |
| `:demo-case-onto-counter` | manipulator | デモ機器ケースを台車から診察室のカウンターへ持ち上げる（2 リンクアーム） | 肩関節ピークトルク | 150 N·m（estimate） |
| `:sample-shipper-wall` | thermal | 35 °C の車内で 8 時間、冷蔵製品サンプルを入れた保冷箱の断熱壁（1-D 伝熱） | 内壁面温度 | 8 °C（WHO TRS 961 Annex 9 の 2〜8 °C。保冷剤側 4 °C は estimate） |

測定の入口: `kbb -M:physics`。全 run が数値を返さなければ exit 2 = **測れなかった**（「異常なし」ではない）。
test: `kbb -M:test`（`test/medsales/physics_spec_test.cljk` が physics.edn の妥当性と全 run の計測を検査する）。
この repo 自身の `.kotoba` test は kbb では走らない（fleet の JVM gate が走らせる）。この bot の test 数は physics の test だけを数える。

## 測って分かったこと・限界（成長の第一候補）

1. **搬送**: 所要時間は積荷 20〜60 kg で 79.8 s、100 kg から駆動力 80 N が律速し 200 kg で 81.96 s。限界 100 s を超える積荷は **328.3 kg**。
   効いているのは速度上限 0.9 m/s と距離で、積荷で変わるのはエネルギー（838.7 J → 3355 J）。
2. **アーム**: 肩トルクは 2 kg で 37.52 N·m、8 kg で 69.5 N·m、16 kg で 115.8 N·m。限界 150 N·m に達する積荷は **21.87 kg**。
3. **保冷箱**: 断熱（PUR 相当 0.025 W/m·K）10 mm で 8 時間後の内壁面 12.86 °C、20 mm で 9.636 °C、30 mm で 8.133 °C、40 mm で 7.263 °C。
   8 °C を守れる厚さは **31.25 mm** 以上。
4. **estimate のままの値**: 区間所要時間 100 s（訪問予約の実測）、肩トルク上限 150 N·m（10 kg 級協働ロボットの仕様書）、
   保冷箱の断熱材物性と熱伝達率・保冷剤側 4 °C の仮定（保冷箱メーカーの認定試験データで置き換える）、内壁面を製品温度の代理にした近似。

## 1 反復の手順（成長 tick）

evidence（prompt に注入される）を読み、次の順で **1 つだけ** 選ぶ:

1. evidence が `TESTS-FAIL` / `PROBE-UNMEASURED` → それを直す（最小の差分）。
2. `physics.edn` の `:basis "estimate: ..."` を 1 つ、出典のある値（規格番号・メーカー仕様・法令の条番号と URL）に置き換える。
   出典が取れなければ置き換えない —— 推測で `estimate` を外さない。
3. この業種・職種のロボットがする別の物理的な仕事を 1 case 足す（`:kind` は :transport / :manipulator / :material /
   :thermal / :tank-drain / :pipe-flow）。README の premise と docs から根拠を取る。
4. governor が同じ solver で独立に再計算して、限界を超える action を止める純関数と test を足す（大きい変更。1〜3 が尽きてから）。

作業の仕方（これ以外の経路で main に入れない）:

```
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk branch physai-isco-2433 <slug>   # worktree を切る（path を印字）
# その worktree で編集 → kbb -M:test → kbb -M:physics → git commit
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk land physai-isco-2433 <branch>   # 検証して merge
```

`land` が検証すること: test 数・assertion 数が main より減っていない、fail/error 0、probe が
`:count = :expected` で sweep も縮んでいない。通らなければ merge しない —— そのときは理由を報告して終える。

## 守ること

- **main に直接 push しない。force-push しない。rebase しない。** 着地は `land` だけ。
- **test を弱めて緑にしない**（assert を消す・sweep を減らす・限界を緩めて合格させる）。`land` は数の減少を拒否する。
- **数値を捏造しない。** 物理量は solver が出したものだけ。`:basis` は出典か `estimate:` のどちらかを必ず書く。
- **実機を動かさない。** これはシミュレーションと governor の repo。`:high` / `:safety-critical` な actuation は
  人の承認なしに commit されない設計を崩さない。
- この repo 以外（kotoba-lang/robotics の solver を含む）は編集しない。solver に足りないものは報告に書く。
- 1 反復で終える。報告は: 選んだ候補 / 変えたこと / test 数の前後 / probe の主要量の前後 / land の結果。誇張しない。
