# Parseable Android

An Android client for [Parseable](https://www.parseable.com/), the open-source log analytics platform. Browse, search, and filter your log data from your phone.

## Features

- **Server Connection** — Connect to any Parseable instance with Basic Auth. Supports both HTTP and HTTPS. Credentials are persisted locally.
- **Stream Browser** — View all log streams with ingestion/storage stats at a glance.
- **Log Viewer** — Browse log entries with expandable detail cards. Tap any entry to see all fields.
- **Time Range Filtering** — Quick-select time ranges from 5 minutes to 30 days.
- **Text Search** — Full-text search across log fields with automatic fallback strategies.
- **Column Filters** — Add SQL-based column filters with operators (=, !=, LIKE, ILIKE, >, <, IS NULL, etc.).
- **Custom SQL Queries** — Write and execute raw PostgreSQL-compatible SQL directly against your log data.
- **Stream Details** — View schema, stats (event count, ingestion size, storage size), and retention config per stream.
- **Alerts** — Browse configured alerts with rule and target details.
- **Settings** — View server info (version, mode, deployment ID, query engine) and user list.
- **Dark Mode** — Full Material 3 dark/light theme support following system preference.
- **Pull-to-Refresh** — Pull to refresh on all list screens.

## Tech Stack

- Kotlin
- Jetpack Compose with Material 3
- Hilt for dependency injection
- OkHttp for networking
- Kotlinx Serialization for JSON
- DataStore for local preferences
- Navigation Compose for routing

## Parseable API Coverage

| Endpoint | Method | Used For |
|---|---|---|
| `/api/v1/liveness` | GET | Connection test |
| `/api/v1/about` | GET | Server info |
| `/api/v1/logstream` | GET | List streams |
| `/api/v1/logstream/{name}/schema` | GET | Stream schema |
| `/api/v1/logstream/{name}/stats` | GET | Stream statistics |
| `/api/v1/logstream/{name}/info` | GET | Stream details |
| `/api/v1/logstream/{name}/retention` | GET | Retention config |
| `/api/v1/logstream/{name}` | DELETE | Delete stream |
| `/api/v1/query` | POST | SQL log queries |
| `/api/v1/alerts` | GET | List alerts |
| `/api/v1/user` | GET | List users |

## Installation

The recommended way to install Parseable Android is via [Obtainium](https://github.com/ImranR98/Obtainium), which will automatically notify you of new releases.

1. Install [Obtainium](https://github.com/ImranR98/Obtainium) on your device.
2. Open Obtainium and tap **Add App**.
3. Enter this repository URL: `https://github.com/ilyakooo0/parseable-android`
4. Tap **Add** — Obtainium will fetch the latest release APK and install it.

Updates will be detected automatically whenever a new release is published.

## Building

```bash
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/`.

## CI/CD

The GitHub Actions workflow (`.github/workflows/build-release.yml`) builds both debug and release APKs. Pushing a tag matching `v*` creates a draft GitHub release with the APKs attached. You can also trigger the workflow manually.

## License

MIT
