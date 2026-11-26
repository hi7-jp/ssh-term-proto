# ssh-term-proto

シンプルな Android 用 SSH ターミナントクライアント（プロトタイプ）。

> ⚠️ **プロトタイプです。** まだ以下のような既知のセキュリティリスクが残っています（詳細は
> [Issues](https://github.com/hi7-jp/ssh-term-proto/issues) を参照）:
> - ホスト鍵検証が無効（MITM に対して無防備）
> - `allowBackup=true` によりプリセット情報が端末外に抜ける可能性
> - パスワードが画面遷移後も残存しうる
>
> 重要な本番サーバーへの接続には今は使わないでください。

## 機能

- host / port / user / password を指定した SSH 接続
- シェルの対話的な入出力
- 接続プリセットの保存・呼び出し（パスワードは保存しません）
- 端末のロック解除（生体認証 / デバイス認証）によるアプリ起動ゲート

## インストール（Obtainium）

本リポジトリの [Releases](https://github.com/hi7-jp/ssh-term-proto/releases) に
タグ push 時に自動で署名済み APK が公開されます。[Obtainium](https://github.com/ImranR98/Obtainium)
で以下の GitHub リポジトリ URL を登録してください。

```
https://github.com/hi7-jp/ssh-term-proto
```

## ビルド

```
./gradlew assembleDebug
```

リリースビルドの署名には環境変数 `ANDROID_KEYSTORE_BASE64` /
`ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD` が必要です
（CI 側で GitHub Secrets から供給されます）。ローカルでこれらを設定しない場合、
リリースビルドは未署名になります。

## ライセンス

[Apache License 2.0](./LICENSE)
