# stock-ranker-android

Kotlin + Jetpack Compose Android client for the stock ranking backend.

## Standalone Mode

The app runs without a separate backend server. It fetches free end-of-day data from Stooq directly on the device and calculates the ranking locally.

## Screens

- Ranking: latest probability-ranked candidates
- Detail: selected ticker signal and price chart
- History: backtest summary
- Settings: API base URL, push registration, disclaimer

## Notifications

The current APK is a standalone ranking app. Push notification delivery is not required for local use; production push support would still need a Firebase project.
