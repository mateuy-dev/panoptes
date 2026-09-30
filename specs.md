# Panoptes — Project Specifications

## Overview

Panoptes is a console application for managing the release lifecycle of a single cross-platform app published across Google Play, Apple App Store, Microsoft Store, and Snap Store. It provides a unified view of the current version on each track and allows promoting builds between tracks.

## Package
The app id is dev.mateuy.panoptes

## Tech Stack

- **Language/Platform**: Kotlin Multiplatform (KMP), targeting JVM for the console app
- **HTTP Client**: Ktor Client
- **Console UI**: [Mosaic](https://github.com/JakeWharton/mosaic)
- **Credential Storage**: AES-256-GCM encrypted file (`~/.panoptes/credentials.enc`), unlocked by a master password at startup

## Functional Requirements

### FR-1: View Current Versions

- Display the current version on each track for each store in a unified dashboard table
- Show: version name, version code / build number, and release status

### FR-2: Promote Between Tracks

- Promote the current build on a source track to the next track
- Supported promotions (where a store has both tracks):
  - internal → alpha
  - alpha → beta
  - beta → production
- User selects store + source track; the app promotes to the next track automatically
- No percentage rollouts — simple, full promotion only

### FR-3: Credential Management

- CLI wizard to configure credentials per store
- Credentials stored encrypted at `~/.panoptes/credentials.enc` (AES-256-GCM)
- Master password prompted at startup
- Credentials and app identifiers can be per-store (different package names / bundle IDs per store are supported)

## Track Mapping

Google Play track names are used as the canonical names across all stores.

| Panoptes Track | Google Play              | App Store Connect          | Microsoft Partner Center | Snap Store |
|----------------|--------------------------|----------------------------|--------------------------|------------|
| `internal`     | Internal Testing         | TestFlight (internal)      | —                        | `edge`     |
| `alpha`        | Closed Testing           | —                          | —                        | `beta`     |
| `beta`         | Open Testing             | —                          | Package Flight           | `candidate`|
| `production`   | Production               | App Store                  | Production               | `stable`   |

Notes:
- App Store only supports `internal` (TestFlight) and `production` (App Store). Only those two tracks are shown for that store.
- Microsoft Store currently only uses `production`, but `beta` via Package Flight is planned. Panoptes will support it once the flight is configured in Partner Center.
- Tracks not supported by a store are hidden from the dashboard and promote flow for that store.

## Store API Details

### Google Play
- **API**: Google Play Developer API v3
- **Auth**: OAuth 2.0 service account (JSON key file)
- **Operations**: list tracks, read current release per track, promote release to a new track

### Apple App Store Connect
- **API**: App Store Connect API
- **Auth**: JWT (issuer ID + key ID + private key `.p8` file)
- **Operations**: list builds, get current TestFlight version, promote to App Store

### Microsoft Partner Center
- **API**: Microsoft Store Submission API
- **Auth**: Azure AD app registration (tenant ID + client ID + client secret)
- **Operations**: get current submission, create/update flight submission, promote flight to production

### Snap Store
- **API**: Snap Store REST API (`dashboard.snapcraft.io`)
- **Auth**: Snapcraft export-login token (macaroon-based, same credential used in `SNAPCRAFT_STORE_CREDENTIALS`)
- **Operations**: read channel map, release a snap revision to a channel

## Architecture

The architecture is layered to allow the UI and store adapters to be swapped independently.

```
┌──────────────────────────────────────────────────┐
│                    UI Layer                       │
│          (Mosaic console / future GUI)            │
├──────────────────────────────────────────────────┤
│               Application Layer                   │
│       Use cases: ViewVersions, PromoteBuild       │
├──────────────────────────────────────────────────┤
│                 Domain Layer                      │
│   Models: Track, Build, TrackVersion, Store       │
│   Interfaces: StoreAdapter, CredentialStore       │
├────────────┬───────────┬────────────┬────────────┤
│ GooglePlay │ AppStore  │  Windows   │    Snap    │
│  Adapter   │  Adapter  │  Adapter   │  Adapter   │
├──────────────────────────────────────────────────┤
│              Infrastructure Layer                 │
│    Ktor HTTP client, encrypted credential store,  │
│    config file reader                             │
└──────────────────────────────────────────────────┘
```

### Key Domain Abstractions

```kotlin
interface StoreAdapter {
    val storeName: String
    val supportedTracks: List<Track>
    suspend fun getVersions(): List<TrackVersion>
    suspend fun promote(fromTrack: Track, toTrack: Track)
}

data class TrackVersion(
    val track: Track,
    val versionName: String,
    val versionCode: Long,
    val status: ReleaseStatus,
)

enum class Track { INTERNAL, ALPHA, BETA, PRODUCTION }

enum class ReleaseStatus { DRAFT, IN_REVIEW, PUBLISHED, HALTED, UNKNOWN }
```

### Configuration

Stored at `~/.panoptes/config.toml`:

```toml
[app]
# Per-store identifiers (can be the same value if shared)
googlePlayPackageName = "com.example.app"
appStoreBundleId      = "com.example.app"
windowsStoreId        = "XXXXXXXXXXXXX"
snapName              = "my-snap"
```

## Console UI (Phase 1)

### Views

1. **Dashboard** — table of all stores × supported tracks showing version name, version code, and status
2. **Promote** — select a store, then a source track, confirm, execute
3. **Settings / Credentials** — wizard to enter/update credentials per store

### Navigation

- Arrow keys / Tab to move between items
- Enter to confirm / select
- Esc to go back / cancel
- `q` to quit

## Out of Scope (Phase 1)

- Uploading or submitting new builds (builds are created by GitHub Actions)
- Editing store metadata (descriptions, screenshots, etc.)
- Percentage / staged rollouts
- Multi-app management
- Notifications or drift alerts
- Graphical UI (architecture allows adding this later)
