# stock-ranker-android

Kotlin + Jetpack Compose Android client for the stock ranking backend.

## Local API

The default backend URL is `http://10.0.2.2:8080`, which points an Android emulator to the host machine. Change `API_BASE_URL` in `app/build.gradle.kts` for a physical device.

## Screens

- Ranking: latest probability-ranked candidates
- Detail: selected ticker signal and price chart
- History: backtest summary
- Settings: API base URL, push registration, disclaimer

## Push Notifications

The app registers an FCM token with `POST /api/devices/register`. Add a Firebase project and `google-services.json` before production distribution. The backend currently contains the FCM send boundary as a placeholder until Firebase Admin credentials are supplied.
