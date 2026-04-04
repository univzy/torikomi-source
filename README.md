# Torikomi Extension Source

Source code for all Torikomi extension APKs.
Each extension is a standalone Kotlin/Android project that exposes a `ContentProvider` IPC interface for the main app.

This repository is the **build source** — compiled APKs and the catalog index live in the separate [torikomi-extensions](https://github.com/univzy/torikomi-extensions) repository.

## Architecture

```
Torikomi App  <->  Extension APK
    |                  |
 PackageManager     ContentProvider
               authority:
               torikomi.extension.<id>
               /scrape?url=...
```

Workflow:
1. Main app discovers installed extensions via `PackageManager` meta-data (`torikomi.extension = "true"`).
2. Main app calls `ContentProvider` with target URL.
3. Extension runs its scraper, returns `ScrapeResult` JSON.
4. Main app deserializes result and presents download options.

## Extensions

| ID | Platform | Lang | Package |
|----|----------|------|---------|
| `tiktok` | TikTok | multi | `com.torikomi.extension_musicaldown` |
| `youtube` | YouTube | multi | `com.torikomi.extension_youtube` |
| `instagram` | Instagram | multi | `com.torikomi.extension_instagram` |
| `facebook` | Facebook | multi | `com.torikomi.extension_facebook` |
| `twitter` | Twitter | multi | `com.torikomi.extension_twitter` |
| `threads` | Threads | multi | `com.torikomi.extension_threads` |
| `pinterest` | Pinterest | multi | `com.torikomi.extension_pinterest` |
| `spotify` | Spotify | multi | `com.torikomi.extension_spotify` |
| `soundcloud` | SoundCloud | multi | `com.torikomi.extension_soundcloud` |
| `douyin` | Douyin | zh | `com.torikomi.extension_douyin` |
| `bilibili` | Bilibili | zh | `com.torikomi.extension_bilibili` |
| `whatsapp_status` | WhatsApp Status | multi | `com.torikomi.extension_whatsapp_status` |

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

The script builds every extension, renames the APK to the catalog naming convention (`torikomi-{lang}.{id}-v{version}.apk`), and copies it to `torikomi-extensions/apk/`.

## Project structure

```
torikomi-extensions-Source/
├── build_all.ps1              # Builds all extensions
├── common/
│   └── ScrapeResult.kt        # Shared data contract (mirrors main app)
└── extensions/
    ├── tiktok/                # Kotlin project
    │   └── android/
    │       └── app/
    │           ├── build.gradle
    │           └── src/main/
    │               ├── AndroidManifest.xml
    │               └── kotlin/com/torikomi/extension_musicaldown/
    │                   ├── ExtensionProvider.kt
    │                   └── MainActivity.kt
    └── ... (same structure for each extension)
```

## Adding a new extension

1. Copy an existing extension folder as template.
2. Replace all occurrences of the old `id`/`package` with the new one.
3. Implement `{Platform}Extension.kt` returning a `ScrapeResult` JSON string.
4. Add an entry to `torikomi-extensions/index.json` and `index.min.json`.
5. Run `build_all.ps1` and upload the new APK.
"# torikomi-source" 
