# stock-ranker-android

Kotlin + Jetpack Compose で作られた株式ランキング Android クライアントです。

## Responses API Android 直接実行モード

Android アプリが Stooq から無料の日足データを取得し、決定論的な指標サマリを計算したうえで、OpenAI Responses API に構造化 JSON 出力でランキング評価を依頼します。

個人利用向け APK として、ビルド時に OpenAI API キーを埋め込んで実行します。

```bash
export OPENAI_API_KEY="your_api_key_here"
./gradlew :app:installDebug
```

または、Gradle property として直接指定できます。

```bash
./gradlew :app:installDebug -PopenAiApiKey="your_api_key_here"
```

使用モデルのデフォルトは `gpt-5.4-mini` です。変更する場合は `-PopenAiModel="model_id"` を指定します。

## 画面

- ランキング: 最新の確率付き上昇候補
- 詳細: 選択した銘柄のシグナルと価格チャート
- 検証: バックテスト概要
- 設定: Android アプリ内算出モードと免責事項

## 通知

ローカル利用ではプッシュ通知の配信は不要です。本番向けにプッシュ通知を有効化する場合は、別途 Firebase プロジェクトが必要です。
