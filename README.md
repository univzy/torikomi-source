# Torikomi Extension Source

Source code and build system for Torikomi extension APKs.

This repository is the **build source**. Published APK files and catalog entries live in [`torikomi-extensions`](https://github.com/univzy/torikomi-extensions).

## Architecture

Each extension is a minimal Android app module (~1–2 MB) that exposes scraping via `ContentProvider` IPC.

```text
Torikomi App -> content://torikomi.extension.<id>/scrape?url=...
```

Workflow:
1. App discovers the extension package via `AndroidManifest.xml` metadata.
2. App invokes the extension's `ContentProvider` with URL + optional CF cookies.
3. Extension scraper returns a JSON payload.
4. App parses the payload into download options and presents them in the template UI.

## Active Modules

| Module ID | Platform | Package | APK |
|---|---|---|---|
| `musicaldown` | TikTok | `com.torikomi.extension_musicaldown` | `torikomi-multi.musicaldown-v1.0.0.apk` |
| `snapsave_twitter` | Twitter/X | `com.torikomi.extension_snapsave_twitter` | `torikomi-multi.snapsave_twitter-v1.0.0.apk` |
| `snapsave_instagram` | Instagram | `com.torikomi.extension_snapsave_instagram` | `torikomi-multi.snapsave_instagram-v1.0.0.apk` |
| `yt1s` | YouTube | `com.torikomi.extension_yt1s` | `torikomi-multi.yt1s-v1.0.0.apk` |

## Build

Requirements:
- Android SDK installed
- JDK 11+
- Gradle wrapper files present (`gradlew` / `gradlew.bat`)

Build all extensions and copy APKs to the catalog folder:

```powershell
.\build_all.ps1 -CatalogPath "..\torikomi-extensions"
```

Build a single extension:

```powershell
.\gradlew.bat :extensions:musicaldown:assembleRelease
.\gradlew.bat :extensions:snapsave_twitter:assembleRelease
.\gradlew.bat :extensions:snapsave_instagram:assembleRelease
.\gradlew.bat :extensions:yt1s:assembleRelease
```

## Repository Layout

```text
torikomi-source/
|-- build_all.ps1          # Build + copy all extensions to catalog
|-- settings.gradle.kts    # Multi-module project definition
|-- common/
|   `-- kotlin/com/torikomi/extension/
|       |-- IExtension.kt  # Shared scraper interface
|       `-- ScrapeResult.kt
`-- extensions/
    |-- musicaldown/
    |-- snapsave_twitter/
    |-- snapsave_instagram/
    `-- yt1s/
```

Each extension module layout:

```text
extensions/<id>/
|-- android/           # Android app wrapper (Manifest, ContentProvider)
|   `-- app/src/main/
|       |-- AndroidManifest.xml
|       `-- kotlin/com/torikomi/extension_<id>/
|           |-- ExtensionProvider.kt
|           `-- MainActivity.kt
`-- src/main/kotlin/   # Scraper business logic
    `-- com/torikomi/extension_<id>/<Platform>Extension.kt
```

## Extension Manifest Metadata

Each `AndroidManifest.xml` declares these metadata keys under the `ContentProvider`:

| Key | Example Value |
|---|---|
| `torikomi.extension.id` | `tiktok` |
| `torikomi.extension.platform` | `tiktok` |
| `torikomi.extension.platformName` | `TikTok` |
| `torikomi.extension.version` | `1.0.0` |
| `torikomi.extension.downloader` | `MusicalDown` |
| `torikomi.extension.description` | `Downloader MusicalDown ...` |
| `torikomi.extension.lang` | `multi` |
| `torikomi.extension.color` | `#000000` |

## Adding a New Extension

1. Copy an existing module under `extensions/` as a template.
2. Update `applicationId`, `package`, `android:authorities`, and all `meta-data` values in `AndroidManifest.xml`.
3. Implement the scraper logic in `src/main/kotlin/…/<Platform>Extension.kt`.
4. Register the new module in `settings.gradle.kts` and the `$AllExtensions` array in `build_all.ps1`.
5. Run `build_all.ps1 -CatalogPath "..\torikomi-extensions"` to build and publish the APK.
6. Add a new entry to `torikomi-extensions/index.json` and regenerate `index.min.json`.
