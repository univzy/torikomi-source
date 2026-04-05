# Torikomi Extension Source

Source code and build system for Torikomi extension APKs.

This repository is the build source. Published APK files and catalog entries are stored in `torikomi-extensions`.

## Architecture

Each extension is an Android app module that exposes scraping via `ContentProvider` IPC.

```text
Torikomi App -> content://torikomi.extension.<id>/scrape?url=...
```

Workflow:
1. App discovers extension package by manifest metadata.
2. App invokes the extension provider with URL and optional cookies.
3. Extension scraper returns JSON payload.
4. App parses payload into download options.

## Active Modules

| Module ID | Platform | Package |
|---|---|---|
| `musicaldown` | TikTok | `com.torikomi.extension_musicaldown` |
| `snapsave_twitter` | Twitter/X | `com.torikomi.extension_snapsave_twitter` |

## Build

Requirements:
- Android SDK installed
- JDK 11+
- Gradle wrapper files present

Build all extension modules:

```powershell
.\build_all.ps1 -CatalogPath "..\torikomi-extensions"
```

Build one module directly:

```powershell
.\gradlew.bat :extensions:musicaldown:assembleRelease
.\gradlew.bat :extensions:snapsave_twitter:assembleRelease
```

## Repository Layout

```text
torikomi-source/
|-- build_all.ps1
|-- settings.gradle.kts
|-- common/
|   `-- kotlin/com/torikomi/extension/IExtension.kt
`-- extensions/
    |-- musicaldown/
    `-- snapsave_twitter/
```

## Adding a New Extension

1. Copy existing module under `extensions/` as template.
2. Update package, application ID, authority, and metadata.
3. Implement scraper logic in `src/main/kotlin`.
4. Register module ID in `settings.gradle.kts` and `build_all.ps1`.
5. Build release APK and publish it to `torikomi-extensions/apk/`.
