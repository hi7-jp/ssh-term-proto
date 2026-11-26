<img src="docs/icon.png" width="96" height="96" alt="ssh-term-proto icon" align="left" />

# ssh-term-proto

シンプルな Android 用 SSH ターミナルクライアント（プロトタイプ）。

<br clear="left" />

## 目次

- [概要](#概要)
- [機能](#機能)
- [インストール](#インストール)
- [ライセンス](#ライセンス)

## 概要

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
- ANSI / VT100 (xterm サブセット) ターミナルエミュレーション
  - 自前実装のパーサー（外部のターミナルエミュレータ・ライブラリには依存せず、Apache-2.0 のまま）
  - カーソル移動、画面／行クリア、スクロール領域、代替スクリーン（`?1049`）、SGR（16 / 256 / TrueColor・太字・反転など）、全角文字（日本語）の幅処理
  - Claude Code や Codex などのフルスクリーン TUI を想定（`TERM=xterm-256color`）
  - キー入力は 1 文字ごとに即送信。Esc / Tab / Ctrl / 矢印などは補助キー行から送信可能（ハードウェアキーボードにも対応）
  - 画面回転・ソフトキーボード表示でのサイズ変化を SSH の window-change で通知
- 接続プリセットの保存・呼び出し（パスワードは保存しません）
- 端末のロック解除（生体認証 / デバイス認証）によるアプリ起動ゲート

## インストール

この Android アプリをインストールするには、以下のいずれかの方法を使ってください。

1. **[Releases](https://github.com/hi7-jp/ssh-term-proto/releases) から APK を直接ダウンロード**してインストールする
2. **[Obtainium](https://github.com/ImranR98/Obtainium) 経由でインストール**し、更新を自動追跡する

   Obtainium で「アプリを追加」から、本リポジトリの URL を登録してください。

   ```
   https://github.com/hi7-jp/ssh-term-proto
   ```

   新しい署名済み APK がリリースされた際、Obtainium から最新版に更新できます
   （現状リリースは手動実行です。自動化は今後の課題）。

## ライセンス

[Apache License 2.0](./LICENSE)
