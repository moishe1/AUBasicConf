# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

AuroraStore - Whitelist Edition is a specialized Aurora Store fork that displays **ONLY whitelisted apps** with automatic remote whitelist updates. Perfect for curated app distribution to specific devices or user groups. It's an Android application written in Kotlin with a mix of Jetpack Compose and traditional Views.

**IMPORTANT**: This is a WHITELIST system - shows ONLY apps that are in your JSON list. If the whitelist is empty, NO APPS will be shown.

## Build Commands

### Gradle Wrapper
```bash
# Build debug APK (vanilla flavor)
./gradlew assembleVanillaDebug

# Build release APK (vanilla flavor, requires signing.properties)
./gradlew assembleVanillaRelease

# Build nightly variant
./gradlew assembleVanillaNightly

# Build with different flavors
./gradlew assembleHuaweiDebug
./gradlew assemblePreloadDebug

# Install debug build to connected device
./gradlew installVanillaDebug

# Clean build
./gradlew clean

# Run tests
./gradlew test

# Run Android instrumented tests
./gradlew connectedAndroidTest

# Lint code
./gradlew lint

# Format code with ktlint
./gradlew ktlintFormat
```

### Build Variants
- `vanilla` (default): Standard version
- `huawei`: Huawei device compatibility version
- `preload`: For preloaded/system installations

### Build Types
- `debug`: Development builds with AOSP signing
- `release`: Production builds (requires signing.properties)
- `nightly`: Development builds with commit hash

## Core Architecture

### Whitelist-First System
- **Exclusive Display**: Only shows apps present in the whitelist - if whitelist is empty/unreachable, NO apps are shown
- **Remote Sync**: Auto-updates whitelist from remote JSON source every 15 seconds while app runs
- **Smart Change Detection**: Only refreshes UI when whitelist actually changes, reducing unnecessary updates
- **Dual Format Support**: Handles both simple package names and complex external app formats

### Key Data Providers
- **WhitelistProvider**: Manages local whitelist storage, external apps, and categorization
- **RemoteWhitelistProvider**: Fetches whitelist from remote JSON sources with change detection
- **ExternalApp Model**: Represents apps outside Play Store ecosystem with custom parsing

### External App Integration
- **Beyond Play Store**: Supports apps not available on Google Play through custom format: `"AppName|packageName|version|apkUrl|iconUrl|category"`
- **Hybrid Loading**: Combines Play Store apps with external apps in unified interface
- **Auth Fallback**: Can operate without Play Store authentication if only external apps are whitelisted

### Core UI Components
- **AppsContainerFragment**: Primary UI showing categorized whitelist apps
- **UpdatesFragment**: Shows available updates for installed whitelisted apps
- **BaseFlavouredSplashFragment**: Smart authentication that skips login if only external apps present
- **WhitelistAppsViewModel**: Handles app fetching, filtering, and download management

### Package Structure
- `com.aurora.store`: Main application package
  - `data/`: Data layer (providers, repositories, database, network)
    - `providers/`: WhitelistProvider, RemoteWhitelistProvider - core whitelist logic
    - `model/`: ExternalApp and other data models
  - `view/`: Views and UI logic (traditional Android Views)
  - `viewmodel/`: ViewModels for MVVM architecture
  - `util/`: Utility classes and managers

### Key Technologies
- **UI**: Mix of Jetpack Compose and traditional Views
- **Architecture**: MVVM with Hilt dependency injection
- **Database**: Room with KSP processing (Schema v5)
- **Networking**: OkHttp with custom GPlayApi integration
- **Async**: Kotlin Coroutines and WorkManager for background sync
- **Image Loading**: Coil
- **Testing**: JUnit, Truth, Espresso, Hilt testing

## Development Notes

### Whitelist Configuration
- Default remote URL: `https://api.github.com/repos/alltechdev/alltech.dev/contents/whitelist.json?ref=main`
- Configure via `PREFERENCE_REMOTE_WHITELIST_URL` preference
- Update `RemoteWhitelistProvider.kt` to change default URL

### Whitelist JSON Formats
```json
// Simple format
["com.package.name1", "com.package.name2"]

// With categories
["com.package.name1 Productivity", "com.package.name2 Games"]

// External apps
["Uber|com.uber.app|1.2.3|https://example.com/uber.apk|https://example.com/icon.png|Transport"]
```

### Signing
- Debug builds use AOSP test key
- Release builds require `signing.properties` file with:
  - `KEY_ALIAS`
  - `KEY_PASSWORD`
  - `STORE_FILE`
  - `KEY_PASSWORD`

### Code Style
- Follows official Kotlin code style
- Ktlint for code formatting
- Proguard enabled for release builds
- Java 21 target

### Special Features
- **15-second auto-refresh**: Automatic whitelist updates from remote source
- **Event-driven UI**: Uses `BusEvent.WhitelistUpdated` for real-time updates
- **Smart authentication**: Skips Play Store login when only external apps are present
- **External app support**: Can operate purely with external APKs without Play Store
- **Categorized display**: Apps organized by category in Apps tab
- **Password-protected app management**: Built-in security features