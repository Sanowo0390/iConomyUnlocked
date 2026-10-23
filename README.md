## iConomyUnlocked (mikan6139 fork)

このリポジトリは [SulkyWhale/iConomyUnlocked](https://github.com/SulkyWhale/iConomyUnlocked) のフォークです。

### ダウンロード
オリジナル版は [Modrinth](https://modrinth.com/plugin/iconomyunlocked) から入手できます。
（このフォーク独自のビルドは配布していません。必要な場合はソースからビルドしてください。）

----
### このフォークについて

本家が止まってそうだったので自分用に直したやつです。

- `/shop`でTPS/MSPTが悪化する不具合を修正（MySQLの接続がプーリングされてなかったのでHikariCPで対応）
- Bedrock(Geyser/Floodgate)プレイヤーが`/money top`に出てこない不具合を修正
- ショップ等での取引で残高に細かい小数点の端数が出る不具合を修正
- `currency_settings.vault_displayed_decimal_places`で小数点の表示桁数を設定できるように
- ついでに見つけた細かいバグもいくつか直してます（`removeCompletely`のバグ、`/money stats`の0除算、MySQL利用時にプールが閉じられない不具合など）

----
### 概要
iConomyの歴史はBukkitプラグイン黎明期、Nijikokun氏の手によるものまで遡ります。7種ほどの派生バージョンを経て、[iConomy 5](https://github.com/iconomy5legacy/iConomy) はElgarL氏、そして最終的にLlmDl氏が引き継ぎました。

シンプルさと安定性で長年多くのサーバーを支えてきたiConomy 5でしたが、[VaultUnlocked](https://github.com/TheNewEconomy/VaultUnlocked) の登場によって、ついにその役目を終える時が来ました。

VaultUnlockedはVaultAPIにようやく正式なUUIDサポートをもたらしました。これにより、Towny のようにプレイヤー以外のアカウント（村・国など）を扱うプラグインも、Vaultの旧式な名前ベースのメソッドを使わずに経済プラグインと連携できるようになります。

iConomyUnlockedは、iConomy 5を使い続けているサーバーに移行先を提供するために作られました。VaultAPIとVaultAPI 2（VaultUnlocked）の両方に対応しているため、Vault系・Vault2系どちらのプラグインとも連携できます。

----
### 特徴
- Vaultベース・Vault2(VaultUnlocked)ベース双方の経済連携プラグインに対応
- Folia対応
- H2・MySQL両方のデータベース形式に対応
- CommentedConfigurationを使用し、設定を保持したままconfigを自動アップデート
- iConomy 5.26からのアカウントインポートに対応

----
### 必要なもの
- [VaultUnlocked](https://github.com/TheNewEconomy/VaultUnlocked) または Vault

### DB待ち時間と集計コマンド

- `/money top` と `/money stats` は専用ワーカーでDBを読み、結果だけをサーバー側のスケジューラで表示します。FoliaではプレイヤーのEntityScheduler、コンソールのGlobalRegionSchedulerを使います。
- 同じ送信者の要求は1件まで、ワーカーの待ち行列は32件までです。DB障害を「口座なし」「空のランキング」として表示しません。
- `database.mysql.connect_timeout_ms`（既定2000）と `socket_timeout_ms`（既定3000）を追加しました。既存の `database.mysql.flags` に同名のConnector/J設定がある場合はそちらを優先します。明示的な `0` は無制限のままです。
- `database.mysql.pool_timeout_ms`（既定2000、最小250）はプールから接続を借りる待ち時間です。通信タイムアウトとは別の設定です。
- HikariCPの未使用接続を減らせるよう `minimumIdle=0`、`idleTimeout=30秒`、`maxLifetime=10分` としています。回収時刻にはHikariCPの管理周期による幅があります。DB側の `wait_timeout` が極端に短い場合まで警告の消失を保証するものではありません。
- 入出金・Vault連携は同期処理です。今回の変更は経済処理全体の非同期化ではなく、通信待ちの制限と読み取り専用集計の非同期化です。

テスト: JDK 17以上で `mvn test`。実際のMariaDB接続、Paper/Spigot/Folia上の表示と停止動作は別途確認してください。

----
### コマンド
> <> は必須、[] は任意
- `/money`: 自分の残高を確認
  - `?`: ヘルプ画面を表示
  - `[playername]`: 他プレイヤーの残高を確認
  - `rank`: 資産ランキングでの自分の順位を確認
  - `rank <playername>`: 他プレイヤーの順位を確認
  - `top [amount]`: 資産ランキング上位を表示
  - `pay <player> <amount>`: 指定プレイヤーに送金
  - `grant <player> <amount> <silent>`: 指定プレイヤーの口座に加算
  - `grant <player> -<amount> <silent>`: 指定プレイヤーの口座から減算
  - `set <player> <amount>`: 指定プレイヤーの残高を設定
  - `hide <player> <true/false>`: ランキング上での表示/非表示を切り替え
  - `create <player>`: デフォルト残高でアカウントを作成
  - `remove <player>`: アカウントを削除
  - `reset <player>`: アカウントをデフォルト残高にリセット
  - `purge`: デフォルト残高のままのアカウントを一括削除
  - `empty`: 全アカウントを削除
  - `stats`: 経済全体の統計を確認
  - `importiconomy`: iConomy 5.26からアカウントをインポート

----
### 権限
```yaml
iConomy.admin:
  default: false
  children:
    iConomy.admin.account.create: true
    iConomy.admin.account.remove: true
    iConomy.admin.reset: true
    iConomy.admin.bank.create: true
    iConomy.admin.empty: true
    iConomy.admin.purge: true
    iConomy.admin.stats: true
    iConomy.admin.grant: true
    iConomy.admin.hide: true
    iConomy.admin.set: true
    iConomy.admin.importiconomy: true
iConomy.access:
  default: true
iConomy.payment:
  default: true
iConomy.rank:
  default: true
iConomy.list:
  default: op
```


### ショップのページ切替で発生するVault残高照会

Vault / VaultUnlocked の `getBalance`・`has`・`hasAccount` は、口座存在確認と残高取得を `SELECT balance ... LIMIT 1` で行います。以前の残高取得は口座検索・名前/UUID取得・存在再確認・残高取得で計4回のSQLを実行していました。

- `database.balance_read_cache_ms`（既定250、0で無効）で、同じUUIDまたは同じ名前の照会結果を短時間再利用します。設定変更は再起動後に反映します。
- キャッシュは最大4096件、UUIDと名前は別キーです。Bedrockのドット付き名前を変更せず検索します。
- このプラグインによる残高変更、口座作成・削除・改名・インポート・一括削除の前後に全照会キャッシュを破棄します。書き込み中と、書き込みにまたがった読み取り結果は保存しません。欠損口座やSQLエラーも保存しません。
- 購入時の `withdraw` / `withdrawPlayer` と `Holdings` の残高確認・更新は従来通りDBを直接使います。入出金の非同期化・書き込みの遅延は行いません。
- 別サーバーや外部SQLから同じDBを更新する場合、照会結果には設定時間までの遅れが生じます。即時反映が必要な構成では0にしてください。
- 初回・期限切れ時のSQLは同期処理です。`/shop` 側のアイテム生成などは対象外で、実際のMSPT改善量は未計測です。

検証: `mvn test`。キャッシュ単体はJDKだけでも次のコマンドで検証できます。

```sh
javac --release 17 -d target/cache-checks src/main/java/io/github/townyadvanced/iconomy/system/BalanceReadCache.java src/test/java/io/github/townyadvanced/iconomy/system/BalanceReadCacheChecks.java
java -cp target/cache-checks io.github.townyadvanced.iconomy.system.BalanceReadCacheChecks
```

実機では、同じショップ・同じ人数でページ連打時のsparkプロファイルとMSPTを修正前後で比較してください。併せて購入・売却直後の残高表示、資金不足時の購入拒否、送金・管理者の残高変更直後の表示、改名・口座削除後の再照会も確認してください。
