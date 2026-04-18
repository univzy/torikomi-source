<p align="center">
  <img src="torikomi.png" width="250" alt="Torikomi" />
</p>

<h1 align="center">Torikomi Extension Source</h1>
<p align="center">
    Source code and build system for all Torikomi extension APKs.
    <br>
    This repository is the <b>build source</b>. Compiled APKs and catalog entries are stored in [`torikomi-extensions`](https://github.com/univzy/torikomi-extensions).
    <br>
    Each extension is a minimal Android app module that implements the `IExtension` interface and exposes scraping logic via `ContentProvider` IPC.
</p>

## Architecture

Each extension is a minimal Android app module (~1–2 MB) that exposes scraping logic via `ContentProvider` IPC.

```
Torikomi App  ──►  content://torikomi.extension.<id>/scrape?url=...&cfCookies=...
                                    │
                              ExtensionProvider
                                    │
                            <Platform>Extension.kt
                                    │
                         JSON payload (ScrapeResult)
                                    │
                   ◄──────────── Torikomi App
```

**Workflow:**
1. App discovers installed extensions via `AndroidManifest.xml` metadata.
2. App invokes the extension's `ContentProvider` with URL + optional CF cookies.
3. Extension scraper returns a JSON payload.
4. App parses the payload into download options and renders them in the UI template.

## Available Extensions

| Module ID | Platform | Package | Downloader | APK |
|---|---|---|---|---|
| `musicaldown` | TikTok | `com.torikomi.extension_musicaldown` | MusicalDown | `torikomi-multi.musicaldown-v1.0.0.apk` |
| `snapsave_twitter` | Twitter/X | `com.torikomi.extension_snapsave_twitter` | SnapSave | `torikomi-multi.snapsave_twitter-v1.0.0.apk` |
| `snapsave_instagram` | Instagram | `com.torikomi.extension_snapsave_instagram` | SnapSave | `torikomi-multi.snapsave_instagram-v1.0.0.apk` |
| `snapsave_threads` | Threads | `com.torikomi.extension_snapsave_threads` | SnapSave | `torikomi-multi.snapsave_threads-v1.0.0.apk` |
| `snapsave_facebook` | Facebook | `com.torikomi.extension_snapsave_facebook` | SnapSave | `torikomi-multi.snapsave_facebook-v1.0.0.apk` |
| `ytdown` | YouTube | `com.torikomi.extension_ytdown` | YTDown | `torikomi-multi.ytdown-v1.0.0.apk` |
| `spotmate` | Spotify | `com.torikomi.extension_spotmate` | Spotmate Downloader | `torikomi-multi.spotmate-v1.0.0.apk` |

## Repository Layout

```text
torikomi-source/
├── build_all.ps1           # Build + copy all extensions to the catalog
├── settings.gradle.kts     # Multi-module project definition
├── common/
│   └── kotlin/com/torikomi/
│       ├── extension/
│       │   └── IExtension.kt       # Shared scraper interface
│       └── browser/
│           └── BrowserCompatibilityManager.kt  # OkHttp TLS helper
└── extensions/
    ├── musicaldown/
    ├── snapsave_twitter/
    ├── snapsave_instagram/
    ├── snapsave_threads/
    ├── snapsave_facebook/
    ├── ytdown/
    └── spotmate/
```

Each extension module follows this layout:

```text
extensions/<id>/
├── proguard-rules.pro
├── src/main/kotlin/com/torikomi/extension_<id>/
│   └── <Platform>Extension.kt     # Core scraping logic
└── android/
    ├── build.gradle.kts
    ├── settings.gradle.kts
    └── app/
        ├── build.gradle
        └── src/main/
            ├── AndroidManifest.xml
            ├── res/drawable/icon_<id>.png
            └── kotlin/com/torikomi/extension_<id>/
                ├── ExtensionProvider.kt   # ContentProvider IPC entry point
                └── MainActivity.kt        # Empty activity (required by Flutter engine)
```

## IExtension Interface

All extensions implement the `IExtension` interface:

```kotlin
interface IExtension {
    fun getId(): String                // Unique extension ID, e.g. "spotmate"
    fun getPlatformId(): String        // Platform ID, e.g. "spotify"
    fun getPlatformName(): String      // Platform display name, e.g. "Spotify"
    fun getVersion(): String           // Semver version
    fun getDownloaderName(): String    // Downloader name
    fun getDownloaderDescription(): String
    fun canHandle(url: String): Boolean
    fun scrape(context: Context, url: String, cfCookies: String? = null): String
}
```

The `scrape()` function returns a JSON string with the following structure:

```json
{
  "extensionId": "spotmate",
  "platform": "spotify",
  "platformName": "Spotify",
  "title": "TWICE - SIGNAL",
  "author": "TWICE",
  "authorName": "TWICE",
  "duration": 196,
  "thumbnail": "https://i.scdn.co/image/...",
  "downloadItems": [
    {
      "key": "audio_mp3",
      "label": "Audio MP3",
      "type": "audio",
      "url": "https://...",
      "mimeType": "audio/mpeg",
      "quality": "MP3"
    }
  ],
  "images": []
}
```

For playlists/albums, item `type` is `"playlist_item"` and `url` contains an individual track link.

## AndroidManifest Metadata

Each `AndroidManifest.xml` declares the following metadata:

| Key | Example Value |
|---|---|
| `torikomi.extension` | `true` |
| `torikomi.extension.id` | `spotmate` |
| `torikomi.extension.platform` | `spotify` |
| `torikomi.extension.platformName` | `Spotify` |
| `torikomi.extension.version` | `1.0.0` |
| `torikomi.extension.downloader` | `Spotmate Downloader` |
| `torikomi.extension.description` | `Download Spotify tracks...` |
| `torikomi.extension.urlPlaceholder` | `https://open.spotify.com/track/...` |
| `torikomi.extension.lang` | `multi` |
| `torikomi.extension.icon` | `spotmate` |
| `torikomi.extension.color` | `#1DB954` |
| `flutterEmbedding` | `2` |

## Build Requirements

- Android SDK (compileSdk 34, minSdk 23)
- JDK 11+
- Gradle wrapper (`gradlew` / `gradlew.bat`) — available in each extension's `android/` folder
- `ANDROID_SDK_ROOT` or `ANDROID_HOME` environment variable, **or** a `local.properties` file with `sdk.dir=<path>`

## Build

### Build all extensions at once

```powershell
.\build_all.ps1 -CatalogPath "..\torikomi-extensions"
```

APKs are compiled and automatically copied to `torikomi-extensions/apk/`.

### Build a specific extension

```powershell
.\build_all.ps1 -Extensions "spotmate" -CatalogPath "..\torikomi-extensions"
```

### Manual Gradle build

```powershell
.\gradlew.bat :extensions:spotmate:assembleRelease
.\gradlew.bat :extensions:musicaldown:assembleRelease
.\gradlew.bat :extensions:ytdown:assembleRelease
```

## Adding a New Extension

1. Copy an existing module as a template:
   ```powershell
   Copy-Item -Recurse extensions\musicaldown extensions\new_ext
   ```
2. Update `extensions\new_ext\android\app\build.gradle`:
   - `namespace`, `applicationId` → `com.torikomi.extension_new_ext`
3. Update `AndroidManifest.xml`:
   - `android:authorities` → `torikomi.extension.new_ext`
   - All `meta-data` values (`id`, `platform`, `platformName`, `downloader`, `color`, etc.)
   - Replace `android:icon` with the new icon
4. Rename the class in `ExtensionProvider.kt` and create `<Platform>Extension.kt` in `src/main/kotlin/`.
5. Add the icon to `android/app/src/main/res/drawable/icon_new_ext.png`.
6. Register in `settings.gradle.kts`:
   ```kotlin
   val extensionIds = listOf(
       // ... existing extensions
       "new_ext"
   )
   ```
7. Register in the `$AllExtensions` array in `build_all.ps1`:
   ```powershell
   @{ id = "new_ext"; lang = "multi"; version = "1.0.0" }
   ```
8. Build: `.\build_all.ps1 -Extensions "new_ext" -CatalogPath "..\torikomi-extensions"`
9. Add a new entry to `torikomi-extensions/index.json` and regenerate `index.min.json`.

## CF Cookies (Cloudflare)

Some extensions (musicaldown, spotmate) require a `cf_clearance` cookie to bypass Cloudflare. This cookie is passed by the main app as the `cfCookies` parameter to the `ContentProvider`:

```
content://torikomi.extension.spotmate/scrape?url=...&cfCookies=cf_clearance%3D...
```

The extension is responsible for including it when making HTTP requests to the target site.
