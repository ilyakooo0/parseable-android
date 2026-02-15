# CLAUDE.md

## Project Overview

Android client for [Parseable](https://www.parseable.com/), an open-source log analytics platform. The app connects to a Parseable server instance and lets users browse, search, filter, and live-tail log data.

## Build & Test

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew assembleRelease        # Build release APK (minified)
./gradlew testDebugUnitTest      # Run unit tests
./gradlew lintDebug              # Run Android lint
```

Requires JDK 17 and the Android SDK (compileSdk 35, minSdk 26).

## Architecture

Single-module Android app (`app/`) using MVVM with Jetpack Compose.

### Layers

- **`data/api/`** — `ParseableApiClient`: OkHttp-based REST client wrapping all Parseable API endpoints. Uses Basic Auth. Returns `ApiResult<T>` (sealed class: `Success`/`Error`).
- **`data/model/`** — Kotlinx Serialization data classes for API request/response types (`LogStream`, `StreamStats`, `StreamSchema`, `Alert`, `QueryRequest`, etc.).
- **`data/repository/`** — `ParseableRepository` (domain facade over the API client) and `SettingsRepository` (DataStore-backed credential persistence).
- **`di/`** — Hilt DI module. All singletons are constructor-injected.
- **`ui/screens/`** — One package per screen, each containing a `*Screen.kt` (Composable) and `*ViewModel.kt` (Hilt ViewModel with `StateFlow`).
- **`ui/navigation/`** — `NavGraph.kt` defines all routes. Navigation uses string-based routes with `{streamName}` path params.
- **`ui/theme/`** — Material 3 theme with Parseable brand colors (purple `#545BEB`). Supports dark/light.

### Screen Map

| Route | Screen | ViewModel |
|---|---|---|
| `login` | `LoginScreen` | `LoginViewModel` |
| `streams` | `StreamsScreen` | `StreamsViewModel` |
| `log_viewer/{streamName}` | `LogViewerScreen` | `LogViewerViewModel` |
| `stream_info/{streamName}` | `StreamInfoScreen` | `StreamInfoViewModel` |
| `alerts` | `AlertsScreen` | `AlertsViewModel` |
| `settings` | `SettingsScreen` | `SettingsViewModel` |

### Parseable API Endpoints Used

All endpoints are under `/api/v1/`. Auth is Basic Auth via `Authorization` header.

- `GET /liveness` — connection test
- `GET /about` — server version/mode info
- `GET /logstream` — list all streams
- `GET /logstream/{name}/schema` — stream field schema
- `GET /logstream/{name}/stats` — ingestion/storage stats
- `GET /logstream/{name}/info` — stream metadata
- `GET /logstream/{name}/retention` — retention config
- `DELETE /logstream/{name}` — delete stream
- `POST /query` — SQL log queries (body: `{query, startTime, endTime}`)
- `GET /alerts` — list alerts
- `GET /user` — list users

API docs: https://www.parseable.com/docs/api

### Key Dependencies

- Jetpack Compose with Material 3 (BOM 2024.12.01)
- Hilt 2.53.1 (DI via KSP)
- OkHttp 4.12.0
- Kotlinx Serialization 1.7.3
- Navigation Compose 2.8.5
- DataStore Preferences 1.1.1

## Code Conventions

- Kotlin with Jetpack Compose — no XML layouts
- State is exposed as `StateFlow` from ViewModels, collected via `collectAsStateWithLifecycle()`
- API responses use `ApiResult<T>` sealed class — always handle both `Success` and `Error` branches
- Network calls use `Dispatchers.IO` via `withContext` inside the API client
- Parseable queries use PostgreSQL-compatible SQL; column names are double-quoted in generated SQL
- Cleartext HTTP is allowed via `network_security_config.xml` (many Parseable instances are self-hosted without TLS)

## CI/CD

- **`.github/workflows/ci.yml`** — Runs on PRs and pushes to main/master. Builds debug APK, runs tests and lint.
- **`.github/workflows/build-release.yml`** — Runs on `v*` tags. Builds debug+release APKs and creates a draft GitHub release.
