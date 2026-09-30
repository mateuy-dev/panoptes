# Panoptes — Implementation Plan

## Context

Panoptes is a new greenfield Kotlin Multiplatform console app to manage the release lifecycle of a cross-platform app across Google Play, Apple App Store, Microsoft Store, and Snap Store. The specs.md is solid; this plan captures additional decisions made before implementation and provides a concrete build roadmap.

## Decisions (supplements specs.md)

| Topic | Decision |
|---|---|
| First run (no config/credentials) | Auto-redirect to credentials/config wizard |
| Dashboard loading | Parallel fetches; per-row loading indicator → fill data or error state |
| Distribution | Fat JAR (`java -jar panoptes.jar`) |
| Testing | Unit tests (mocked adapters) + integration tests (real/sandbox APIs) |
| File credentials (Google JSON, Apple .p8) | User provides path; Panoptes reads and stores file content encrypted |
| Microsoft Package Flight (beta) | Fully implement in Phase 1 |
| Dependency injection | Koin |

## Project Structure

```
panoptes/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/libs.versions.toml
└── src/
    ├── commonMain/kotlin/dev/mateuy/panoptes/
    │   ├── domain/
    │   │   ├── model/        # Track, Build, TrackVersion, ReleaseStatus, Store
    │   │   └── port/         # StoreAdapter, CredentialStore interfaces
    │   ├── application/
    │   │   ├── ViewVersionsUseCase.kt
    │   │   └── PromoteBuildUseCase.kt
    │   ├── adapter/
    │   │   ├── googleplay/   # GooglePlayAdapter + API DTOs
    │   │   ├── appstore/     # AppStoreAdapter + JWT auth + API DTOs
    │   │   ├── microsoft/    # MicrosoftAdapter (production + Package Flight)
    │   │   └── snap/         # SnapAdapter + macaroon auth
    │   ├── infrastructure/
    │   │   ├── CredentialStoreImpl.kt   # AES-256-GCM encrypted file
    │   │   ├── ConfigReader.kt          # ~/.panoptes/config.toml parser
    │   │   └── HttpClientFactory.kt     # Ktor client setup
    │   ├── ui/
    │   │   ├── DashboardView.kt         # Mosaic table (stores × tracks)
    │   │   ├── PromoteView.kt           # Store + track selection + confirm
    │   │   ├── SettingsWizard.kt        # Per-store credential entry
    │   │   └── Navigation.kt            # App-level state machine
    │   ├── di/
    │   │   └── AppModule.kt             # Koin module definitions
    │   └── Main.kt                      # Entry point: master password → Koin start → UI
    └── commonTest/kotlin/dev/mateuy/panoptes/
        ├── domain/                      # Unit tests with mocked adapters
        └── adapter/                     # Integration tests (real APIs, opt-in)
```

## Implementation Steps

### Step 1: Project Setup
- Create `settings.gradle.kts`, `build.gradle.kts` (KMP, JVM target, fat JAR task)
- Create `gradle/libs.versions.toml` with:
  - Kotlin / KMP
  - Ktor client (CIO engine for JVM)
  - Mosaic
  - Koin
  - kotlinx-serialization (JSON)
  - kotlinx-coroutines
  - tomlkt or kaml (TOML config parsing)
  - kotlin-test + mockk (testing)

### Step 2: Domain Layer
- `Track.kt`: `enum class Track { INTERNAL, ALPHA, BETA, PRODUCTION }`
- `ReleaseStatus.kt`: `enum class ReleaseStatus { DRAFT, IN_REVIEW, PUBLISHED, HALTED, UNKNOWN }`
- `TrackVersion.kt`: data class with track, versionName, versionCode, status
- `StoreAdapter.kt`: interface with storeName, supportedTracks, getVersions(), promote()
- `CredentialStore.kt`: interface for get/set per-store credentials (map of key→value)

### Step 3: Infrastructure Layer
- `CredentialStoreImpl.kt`: AES-256-GCM encryption; master password → PBKDF2 key derivation; store/load from `~/.panoptes/credentials.enc`
- `ConfigReader.kt`: read/write `~/.panoptes/config.toml`; detect missing file → first-run flag
- `HttpClientFactory.kt`: Ktor CIO client with JSON content negotiation and logging

### Step 4: Store Adapters

**GooglePlayAdapter**
- Auth: load service account JSON, exchange for OAuth 2.0 Bearer token
- `getVersions()`: GET `androidpublisher/v3/applications/{packageName}/tracks`
- `promote()`: PATCH track with updated release referencing previous track's versionCode

**AppStoreAdapter**
- Auth: generate JWT (ES256) from issuer ID + key ID + .p8 private key content; refresh every 20 min
- Supported tracks: `[INTERNAL, PRODUCTION]`
- `getVersions()`: GET builds + app store version
- `promote()`: POST to submit build for TestFlight or App Store review

**MicrosoftAdapter**
- Auth: Azure AD client credentials flow → Bearer token
- Supported tracks: `[BETA, PRODUCTION]`
- `getVersions()`: GET current submission + flight submission
- `promote()`: clone flight submission → commit → submit

**SnapAdapter**
- Auth: Snapcraft macaroon token in `Authorization: Macaroon` header
- `getVersions()`: GET `https://dashboard.snapcraft.io/dev/api/snaps/{snap_name}/channel-map`
- `promote()`: POST release endpoint with revision + channel

### Step 5: Application Layer
- `ViewVersionsUseCase`: runs all adapters' `getVersions()` in parallel (coroutines), emits per-store results as a `Flow<StoreResult>` for streaming dashboard updates
- `PromoteBuildUseCase`: validates promotion is allowed, calls adapter `promote()`

### Step 6: DI (Koin)
- `AppModule.kt`: bind `CredentialStoreImpl`, `ConfigReader`, `HttpClientFactory`, all 4 adapters, both use cases
- Wire in `Main.kt`: init Koin → inject master password → start Mosaic UI

### Step 7: UI Layer (Mosaic)
- **Navigation**: sealed class `Screen { Dashboard, Promote, Settings }` with state hoisting
- **DashboardView**: table rows = stores, columns = tracks; each cell shows versionName/versionCode/status or spinner or error; collect from `ViewVersionsUseCase` Flow
- **PromoteView**: step 1 select store, step 2 select source track, step 3 confirm → call `PromoteBuildUseCase` → show result
- **SettingsWizard**: per-store sequence of prompts; file path fields → read file content; save via `CredentialStore` + `ConfigReader`
- **First-run detection**: if config missing → go directly to `Settings` screen on startup

### Step 8: Entry Point
- Prompt for master password (masked input)
- Attempt to decrypt credentials; if wrong password → re-prompt (max 3 attempts)
- If first run (no config) → navigate to Settings
- Otherwise → navigate to Dashboard

### Step 9: Tests
- **Unit**: mock all `StoreAdapter`s, test use cases, test credential encryption round-trip, test track mapping logic
- **Integration**: real API tests gated behind a system property or env var (e.g., `PANOPTES_INTEGRATION_TESTS=true`); one test per store adapter

## Key Files to Create

| File | Purpose |
|---|---|
| `build.gradle.kts` | KMP project, fat JAR config |
| `gradle/libs.versions.toml` | Dependency version catalog |
| `src/.../domain/port/StoreAdapter.kt` | Core interface all adapters implement |
| `src/.../infrastructure/CredentialStoreImpl.kt` | Encrypted credential storage |
| `src/.../adapter/googleplay/GooglePlayAdapter.kt` | Google Play integration |
| `src/.../adapter/appstore/AppStoreAdapter.kt` | App Store Connect integration |
| `src/.../adapter/microsoft/MicrosoftAdapter.kt` | Microsoft Partner Center + Flight |
| `src/.../adapter/snap/SnapAdapter.kt` | Snap Store integration |
| `src/.../application/ViewVersionsUseCase.kt` | Parallel version fetching |
| `src/.../ui/DashboardView.kt` | Main Mosaic dashboard |
| `src/.../Main.kt` | Entry point + Koin wiring |

## Verification

1. `./gradlew run` — launches the app; verify first-run wizard appears when no config exists
2. Configure credentials for one store (e.g., Google Play sandbox account)
3. Verify dashboard shows version data for that store with loading states working
4. Test `q` to quit, Esc to cancel promote flow, arrow key navigation
5. `./gradlew shadowJar` (or equivalent) — verify fat JAR is produced and runnable with `java -jar`
6. `./gradlew test` — unit tests pass with mocked adapters
7. `PANOPTES_INTEGRATION_TESTS=true ./gradlew test` — integration tests hit real APIs
