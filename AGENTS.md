# AGENTS.md

## Project Rules

- After any Android build work, always run or confirm release package generation.
- Preferred verification:
  - `./gradlew :app:build`
  - Confirm `app/build/outputs/release-package/stock-ranker-release.apk` exists.
- Release builds are signed with the local debug keystore for personal/device testing.
- Do not treat this APK as Play Store production-signed.

## OpenAI API

- The Android app calls the OpenAI Responses API directly.
- `OPENAI_API_KEY` is embedded at build time from:
  - Gradle property `openAiApiKey`
  - environment variable `OPENAI_API_KEY`
  - `local.properties`
- Do not commit API keys.
- For 429 errors, inspect the on-screen details:
  - `message`
  - `type`
  - `code`
  - `request_id`
  - `retry_after`
  - `x-ratelimit-*`
  - response body preview

## Useful Commands

```bash
./gradlew :app:build
./gradlew :app:assembleRelease
./gradlew :app:createReleasePackage
```
