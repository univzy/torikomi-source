# AIO Extension Source

Source code for all AIO Downloader extension APKs.
Each extension is a standalone Flutter/Android project that exposes a `ContentProvider` IPC interface for the main [AIO Downloader](https://github.com/univzy/torikomi-dev-Dart) app.

This repository is the **build source** — compiled APKs and the catalog index live in the separate [torikomi-extensions](https://github.com/univzy/torikomi-extensions) repository.

## Architecture

```
Torikomi-Dart  ←→  Extension APK
        │                     │
  ExtensionBridge.dart   ContentProvider
  (MethodChannel)         authority:
                    aio.extension.<id>
                    /scrape?url=...
```

Workflow:
1. Main app discovers installed extensions via `PackageManager` meta-data (`aio.extension = "true"`).
2. Main app calls `ContentProvider` with target URL.
3. Extension runs its scraper, returns `ScrapeResult` JSON.
4. Main app deserializes result and presents download options.

## Extensions

| ID | Platform | Lang | Package |
|----|----------|------|---------|
| `tiktok` | TikTok | multi | `com.tobz.aio_extension_tiktok` |
| `youtube` | YouTube | multi | `com.tobz.aio_extension_youtube` |
| `instagram` | Instagram | multi | `com.tobz.aio_extension_instagram` |
| `facebook` | Facebook | multi | `com.tobz.aio_extension_facebook` |
| `twitter` | Twitter | multi | `com.tobz.aio_extension_twitter` |
| `threads` | Threads | multi | `com.tobz.aio_extension_threads` |
| `pinterest` | Pinterest | multi | `com.tobz.aio_extension_pinterest` |
| `spotify` | Spotify | multi | `com.tobz.aio_extension_spotify` |
| `soundcloud` | SoundCloud | multi | `com.tobz.aio_extension_soundcloud` |
| `douyin` | Douyin | zh | `com.tobz.aio_extension_douyin` |
| `bilibili` | Bilibili | zh | `com.tobz.aio_extension_bilibili` |
| `whatsapp_status` | WhatsApp Status | multi | `com.tobz.aio_extension_whatsapp_status` |

## Building

### Prerequisites
- Flutter SDK ≥ 3.22
- Android SDK with build-tools 34+
- `keytool` and `apksigner` on PATH (or use Android Studio)

### Build a single extension
```powershell
cd extensions\tiktok
flutter build apk --release
# Output: build\app\outputs\flutter-apk\app-release.apk
```

### Build all extensions + copy to torikomi-extensions catalog
```powershell
.\build_all.ps1 -CatalogPath "..\torikomi-extensions"
```

The script builds every extension, renames the APK to the catalog naming convention (`aio-{lang}.{id}-v{version}.apk`), and copies it to `torikomi-extensions/apk/`.

## Project structure

```
torikomi-extensions-Source/
├── build_all.ps1              # Builds all extensions
├── common/
│   └── scrape_result.dart     # Shared data contract (mirrors main app)
└── extensions/
    ├── tiktok/                # Flutter project
    │   ├── pubspec.yaml
    │   ├── lib/
    │   │   ├── main.dart
    │   │   ├── scrape_result.dart
    │   │   └── scraper/
    │   │       └── tiktok_scraper.dart
    │   └── android/
    │       └── app/
    │           ├── build.gradle
    │           └── src/main/
    │               ├── AndroidManifest.xml
    │               └── kotlin/com/tobz/aio_extension_tiktok/
    │                   ├── ExtensionProvider.kt
    │                   └── MainActivity.kt
    └── ... (same structure for each extension)
```

## Adding a new extension

1. Copy an existing extension folder as template.
2. Replace all occurrences of the old `id`/`package` with the new one.
3. Implement `lib/scraper/{id}_scraper.dart` returning a `ScrapeResult`.
4. Add an entry to `torikomi-extensions/index.json` and `index.min.json`.
5. Run `build_all.ps1` and upload the new APK.
"# torikomi-source" 
