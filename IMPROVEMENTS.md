# Parseable Android — Improvements

Summary of all improvements made to the codebase, organized by category.

---

## Security

### SQL Injection Prevention
- **LogViewerViewModel**: All user-supplied column names are escaped with `escapeIdentifier()` (double-quote escaping) and filter values use single-quote escaping (`'` → `''`) to prevent SQL injection in generated WHERE clauses.
- **Custom SQL Queries**: Only `SELECT` statements are allowed; `DELETE`, `DROP`, etc. are rejected. Queries without a `LIMIT` clause automatically get `LIMIT 5000` appended to prevent OOM from unbounded result sets.
- **ParseableRepository**: Stream names in generated SQL are escaped with `escapeIdentifier()`.

### Credential Storage
- **SettingsRepository**: Passwords are stored in `EncryptedSharedPreferences` (AES-256-GCM) backed by Android Keystore, separate from other preferences in DataStore.
- **Corruption Recovery**: If `EncryptedSharedPreferences` becomes corrupted (common after backup/restore), the file is deleted and recreated automatically with a Timber log.
- **Mutex Protection**: A `configMutex` guards concurrent reads/writes across DataStore and EncryptedSharedPreferences.

### Network Security
- **network_security_config.xml**: Cleartext HTTP is explicitly allowed (required for self-hosted Parseable instances without TLS) with a proper XML config rather than a blanket manifest flag.
- **Private IP Detection**: LoginScreen warns users when enabling HTTP for non-private IP addresses (checks `localhost`, `127.*`, `10.*`, `192.168.*`, `172.16-31.*`).
- **Clipboard Safety**: Log export catches `SecurityException` when accessing the clipboard on Android 13+ where background clipboard access may be restricted.

### Input Validation
- **URL Validation**: Server URLs are validated with `Patterns.WEB_URL`, trimmed of whitespace, and checked for blank hosts before connection attempts.
- **Filter Operators**: Only operators from an `ALLOWED_OPERATORS` allowlist are accepted for filter clauses.

---

## Concurrency & Reliability

### CancellationException Handling
- **All ViewModels** (`SettingsViewModel`, `StreamsViewModel`, `StreamInfoViewModel`, `LogViewerViewModel`): `async/await` blocks are wrapped in `try-catch` with explicit `CancellationException` handling. On cancellation (e.g., user navigates away), `isLoading` is reset to `false` before re-throwing, preventing the UI from getting stuck in a permanent loading state.

### Structured Concurrency
- **LogViewerViewModel**: Schema loading uses a dedicated `schemaJob` that is cancelled on re-initialization or `onCleared()`. Search debounce uses a separate `searchJob`. Streaming uses `streamingJob` with a generation counter to prevent stale poll results.
- **StreamsViewModel**: Stats loading uses a `statsJob` that is cancelled on refresh. A `Semaphore(8)` limits concurrent stats requests to 8 at a time.
- **StreamInfoViewModel**: A `loadJob` is cancelled when `load()` is called again, preventing duplicate parallel loads.

### Thread Safety
- **LogViewerViewModel**: `streamingGeneration`, `lastSeenTimestamp`, and `consecutiveStreamingErrors` are all `@Volatile` to ensure visibility across coroutines.
- **ParseableApiClient**: The `config` field is `@Volatile` and `buildRequest()` captures a local snapshot to avoid reading a partially-updated config during concurrent requests.

### Resource Cleanup
- **ParseableApiClient**: HTTP responses use `.use { }` blocks to ensure the response body is always closed, even on exceptions. A `shutdown()` method evicts the connection pool and shuts down the dispatcher.
- **ViewModels**: All `onCleared()` implementations cancel active jobs (`schemaJob`, `searchJob`, `streamingJob`).
- **Streaming**: Consecutive errors during streaming trigger an exponential backoff (1s → 2s → 4s) and auto-stop after 10 consecutive failures with an error message.

### Logout Race Condition
- **StreamsViewModel**: `logout()` calls `clearConfig()` and `clearCredentials()` sequentially to prevent auth errors from triggering a second logout navigation.
- **ParseableRepository**: Auth errors (401) are detected via `checkAuth()` which clears credentials and sends to `authErrors` channel (conflated to prevent duplicate navigations).

---

## Error Handling & UX

### Error Messages
- **ParseableApiClient**: Specific exception types produce targeted messages:
  - `SocketTimeoutException` → "Connection timed out"
  - `UnknownHostException` → "Unable to resolve host"
  - `ConnectException` → "Connection refused. Is the server running?"
  - `SSLException` → "SSL error: ... Check server certificate."
  - Generic `IOException` → the exception message or "Network error"
- **SettingsScreen**: Error card is now visible even during a reload (removed the `!isLoading` gate that hid errors during pull-to-refresh).

### Empty States
- **LogViewerScreen**: Context-aware empty messages:
  - During streaming: "Waiting for new logs…"
  - With search + filters: "No logs match \"query\" with the active filters"
  - With search only: "No logs match \"query\""
  - With filters only: "No logs match the active filters"
  - Default: "No logs found for the selected time range"
- **Result Cap Warning**: When results hit the 5,000 limit, shows "Showing max 5,000 results. Refine your query to see more." in primary color instead of misleading "End of results".

### Streaming Error Persistence
- **LogViewerViewModel**: `stopStreaming()` now preserves the streaming error in state (only clears `isStreaming`). A separate `dismissStreamingError()` method lets users explicitly dismiss the error banner. Errors are also cleared on `refresh()`.

### Schema Failure Visibility
- **StreamInfoViewModel**: A `schemaFailed` boolean flag tracks whether the schema request specifically failed (separate from the combined error string).
- **StreamInfoScreen**: Shows a "Schema unavailable" card when schema loading fails, instead of silently hiding the entire section.

### Alert Deletion Safety
- **AlertsScreen**: Alerts without an `id` field have their delete button disabled at the card level, preventing them from reaching the delete confirmation dialog.

---

## Accessibility

### Touch Targets
- All interactive elements meet the 48dp minimum touch target size:
  - **StreamsScreen**: Stats retry `TextButton` uses `defaultMinSize(minHeight = 48.dp)` instead of a fixed 24dp height.
  - **LogViewerScreen**: Filter chips, time range buttons, and all `IconButton` elements meet the 48dp minimum.
  - **LoginScreen**: Both login buttons use `defaultMinSize(minHeight = 50.dp)` instead of fixed `.height(50.dp)`, allowing growth for landscape or accessibility text scaling.

### Content Descriptions
- **StreamsScreen**: The decorative `ChevronRight` icon uses `contentDescription = null` since the parent `Card` already handles the click action (avoids duplicate screen reader announcements).
- All other icons have meaningful `contentDescription` values that describe the action, not the icon.

### Keyboard / IME
- **LogViewerScreen**: Pressing the keyboard Search button (`ImeAction.Search`) now triggers `viewModel.refresh()` in addition to hiding the keyboard.
- **Filter Bottom Sheet**: The filter value field has `ImeAction.Done` with a `keyboardActions` handler that applies the filter directly from the keyboard.
- **LoginScreen**: IME actions chain correctly: URL → Next → Username → Next → Password → Done (triggers login).

### Configuration Changes
- **LoginScreen**: `passwordVisible` uses `rememberSaveable` instead of `remember`, so the "show password" toggle survives device rotation.

---

## Performance

### List Operations
- **LogViewerViewModel**: `removeFilter()` uses `filterIndexed` instead of `toMutableList().apply { removeAt() }`, avoiding temporary mutable list allocations.
- **LazyColumn Keys**: All `LazyColumn` lists use deterministic, stable keys (e.g., `alert.id ?: alert.name ?: "alert_$index"`) to prevent unnecessary recompositions.

### Streaming Backpressure
- **LogViewerViewModel**: Streaming caps at `STREAMING_MAX_LOGS = 1000` entries, dropping oldest when the cap is reached. A generation counter prevents stale poll results from being applied.

### Caching
- **ParseableRepository**: TTL-based caching with `ConcurrentHashMap`:
  - Streams: 30s
  - Schema: 2min
  - Stats: 30s
  - About: 5min
- Force-refresh bypasses the cache when explicitly requested (e.g., pull-to-refresh).

### R8 Full Mode
- **build.gradle.kts**: `isMinifyEnabled = true` with R8 full mode enabled for release builds.
- **proguard-rules.pro**: Comprehensive keep rules for kotlinx.serialization, Room entities, ViewModel state classes, OkHttp, EncryptedSharedPreferences, Google Tink, and Protobuf.

---

## Testing

### Unit Test Coverage
Every ViewModel has a dedicated test class:

| Test Class | Tests | Covers |
|---|---|---|
| `LogViewerViewModelTest` | 17 | Initialize, search, filters, time ranges, streaming, custom SQL LIMIT, SQL escaping, IS NULL, error states |
| `StreamsViewModelTest` | 8 | Refresh, error handling, stats loading, favorites CRUD, exception resilience |
| `SettingsViewModelTest` | 8 | Config loading, about info, user parsing, error consolidation, exception resilience, refresh |
| `StreamInfoViewModelTest` | 9 | Parallel loading, partial errors, schemaFailed flag, delete, cancel, idempotency |
| `AlertsViewModelTest` | 7 | Refresh, error, delete flow, cancel delete |
| `LoginViewModelTest` | 9+ | Validation, URL normalization, login flow, saved credentials |
| `ParseableRepositoryTest` | 9+ | Caching, TTL, auth error detection, cache invalidation |

### CI
- **ci.yml**: Runs `testDebugUnitTest` and `lintDebug` on PRs and pushes to main/master.
- **build-release.yml**: Builds both debug and release APKs on `v*` tags and creates a draft GitHub release.

---

## Architecture & Code Quality

### Error Handler
- **ErrorHandler**: A `CompositionLocal`-based error handler (`LocalErrorHandler`) provides a centralized `SnackbarHostState` for showing error messages from any screen. Used for session-expired notifications on the login screen.

### Navigation
- **NavGraph**: All authenticated routes (`streams`, `log_viewer`, `stream_info`, `alerts`, `settings`) have auth guards that redirect to login if the API client isn't configured.
- **URL Encoding**: Stream names are URL-encoded/decoded in navigation routes to handle special characters.
- **Deep Links**: Supported for `parseable://streams`, `parseable://stream/{streamName}`, and `parseable://alerts`.

### Favorites (Room)
- **FavoriteStream**: Room entity with a DAO for persisting favorite streams locally.
- **ParseableDatabase**: Room database with `fallbackToDestructiveMigration()` and schema export directory.
- **StreamsScreen**: Favorite streams are sorted to the top of the list. Toggle via star icon with snackbar feedback.

### Dependency Management
- **libs.versions.toml**: All dependency versions are centralized in a Gradle version catalog.
- **Timber**: Debug logging via `Timber.DebugTree()` planted only in debug builds. Used throughout the API client and repository layer for error logging.

---

## Files Changed

41 files modified across the codebase, with approximately **2,981 lines added** and **664 lines removed**.
