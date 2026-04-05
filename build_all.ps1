<#
.SYNOPSIS
    Build all Torikomi Kotlin extensions to minimal APKs (~500KB-1MB each).
    
    Kotlin extensions (not Flutter) = pure Java/Kotlin code compiled to DEX.
    Result: 500KB-1MB per extension (vs 16-20MB for Flutter).

.PARAMETER CatalogPath
    Path to output folder. Defaults to torikomi-extensions.

.PARAMETER Extensions
    Comma-separated list to build (defaults to all).

.EXAMPLE
    .\build_all.ps1
    .\build_all.ps1 -Extensions "musicaldown"
#>
param(
    [string]$CatalogPath = "",
    [string]$Extensions = ""
)

$ErrorActionPreference = "Stop"

# ── Ensure Android SDK location for Gradle builds ───────────────────────────
function Ensure-LocalProperties {
    param([string]$ProjectRoot)

    $localProps = Join-Path $ProjectRoot "local.properties"
    if (Test-Path $localProps) {
        return
    }

    $sdkPath = $env:ANDROID_SDK_ROOT
    if ([string]::IsNullOrWhiteSpace($sdkPath)) {
        $sdkPath = $env:ANDROID_HOME
    }

    if ([string]::IsNullOrWhiteSpace($sdkPath) -or -not (Test-Path $sdkPath)) {
        throw "Android SDK not found. Set ANDROID_SDK_ROOT/ANDROID_HOME or create $localProps with sdk.dir=<path>."
    }

    $escaped = $sdkPath -replace '\\', '\\\\'
    Set-Content -Path $localProps -Encoding UTF8 -Value "sdk.dir=$escaped"
    Write-Host "[bootstrap] Created local.properties with sdk.dir"
}

# ── Reference project (used to copy gradle wrapper binaries) ─────────────────
$ReferenceAndroid = Join-Path $PSScriptRoot "..\torikomi-kotlin"

# ── Bootstrap a minimal Android project structure (Kotlin only) ──────────────
function Initialize-AndroidProject {
    param([string]$AndroidDir)

    if (-not (Test-Path $AndroidDir)) {
        New-Item -ItemType Directory -Path $AndroidDir | Out-Null
    }

    # settings.gradle.kts
    $settingsFile = Join-Path $AndroidDir "settings.gradle.kts"
    if (-not (Test-Path $settingsFile)) {
        Set-Content -Path $settingsFile -Encoding UTF8 -Value @'
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}

include(":app")
'@
        Write-Host "    [bootstrap] Created settings.gradle.kts"
    }
    if (Test-Path $settingsFile) {
        $settingsRaw = Get-Content $settingsFile -Raw
        $settingsNormalized = $settingsRaw
        $settingsNormalized = $settingsNormalized -replace 'id\("dev\.flutter\.flutter-plugin-loader"\) version "1\.0\.0"\s*', ''
        $settingsNormalized = $settingsNormalized -replace 'id\("org\.jetbrains\.kotlin\.android"\) version "1\.9\.25" apply false', 'id("org.jetbrains.kotlin.android") version "2.1.0" apply false'
        if ($settingsNormalized -ne $settingsRaw) {
            Set-Content -Path $settingsFile -Encoding UTF8 -Value $settingsNormalized
            Write-Host "    [bootstrap] Normalized settings.gradle.kts"
        }
    }

    # build.gradle.kts (root)
    $rootBuildFile = Join-Path $AndroidDir "build.gradle.kts"
    if (-not (Test-Path $rootBuildFile)) {
        Set-Content -Path $rootBuildFile -Encoding UTF8 -Value @'
allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
'@
        Write-Host "    [bootstrap] Created build.gradle.kts"
    }

    # gradle.properties
    $propsFile = Join-Path $AndroidDir "gradle.properties"
    $desiredGradleProperties = @"
org.gradle.jvmargs=-Xmx6G -XX:MaxMetaspaceSize=4G -Dfile.encoding=UTF-8
android.useAndroidX=true
android.enableJetifier=false
kotlin.jvm.target.validation.mode=WARNING
"@
    if (-not (Test-Path $propsFile)) {
        Set-Content -Path $propsFile -Encoding UTF8 -Value $desiredGradleProperties
        Write-Host "    [bootstrap] Created gradle.properties"
    } else {
        Set-Content -Path $propsFile -Encoding UTF8 -Value $desiredGradleProperties
        Write-Host "    [bootstrap] Normalized gradle.properties"
    }

    # gradle/wrapper/gradle-wrapper.properties + gradle-wrapper.jar + scripts
    $wrapperDir = Join-Path $AndroidDir "gradle\wrapper"
    if (-not (Test-Path $wrapperDir)) {
        New-Item -ItemType Directory -Path $wrapperDir | Out-Null
    }

    $wrapperProps = Join-Path $wrapperDir "gradle-wrapper.properties"
    $desiredWrapperProperties = @"
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.9-all.zip
networkTimeout=10000
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
"@
    if (-not (Test-Path $wrapperProps)) {
        Set-Content -Path $wrapperProps -Encoding UTF8 -Value $desiredWrapperProperties
        Write-Host "    [bootstrap] Created gradle-wrapper.properties"
    } else {
        Set-Content -Path $wrapperProps -Encoding UTF8 -Value $desiredWrapperProperties
        Write-Host "    [bootstrap] Normalized gradle-wrapper.properties"
    }

    $wrapperJar = Join-Path $wrapperDir "gradle-wrapper.jar"
    if (-not (Test-Path $wrapperJar)) {
        $refJar = Join-Path $ReferenceAndroid "gradle\wrapper\gradle-wrapper.jar"
        if (Test-Path $refJar) {
            Copy-Item -Path $refJar -Destination $wrapperJar
            Write-Host "    [bootstrap] Copied gradle-wrapper.jar"
        } else {
            Write-Warning "    [bootstrap] gradle-wrapper.jar not found at: $refJar"
        }
    }

    $gradlew = Join-Path $AndroidDir "gradlew"
    if (-not (Test-Path $gradlew)) {
        $refGradlew = Join-Path $ReferenceAndroid "gradlew"
        if (Test-Path $refGradlew) {
            Copy-Item -Path $refGradlew -Destination $gradlew
            Copy-Item -Path (Join-Path $ReferenceAndroid "gradlew.bat") -Destination (Join-Path $AndroidDir "gradlew.bat") -ErrorAction SilentlyContinue
            Write-Host "    [bootstrap] Copied gradlew scripts"
        } else {
            Write-Warning "    [bootstrap] gradlew not found at: $refGradlew"
        }
    }

    $appBuildGradle = Join-Path $AndroidDir "app\build.gradle"
    if (Test-Path $appBuildGradle) {
        $appBuildRaw = Get-Content $appBuildGradle -Raw
        $appBuildNormalized = $appBuildRaw -replace 'minSdk\s+21\b', 'minSdk 23'
        if ($appBuildNormalized -ne $appBuildRaw) {
            Set-Content -Path $appBuildGradle -Encoding UTF8 -Value $appBuildNormalized
            Write-Host "    [bootstrap] Normalized minSdk to 23 in app/build.gradle"
        }
    }

    $mainManifest = Join-Path $AndroidDir "app\src\main\AndroidManifest.xml"
    if (Test-Path $mainManifest) {
        $manifestRaw = Get-Content $mainManifest -Raw
        $manifestNormalized = $manifestRaw -replace '\s+android:icon="@mipmap/ic_launcher"', ''
        if ($manifestNormalized -ne $manifestRaw) {
            Set-Content -Path $mainManifest -Encoding UTF8 -Value $manifestNormalized
            Write-Host "    [bootstrap] Removed missing launcher icon reference"
        }
    }
}

# ── Extension manifest ───────────────────────────────────────────────────────
$AllExtensions = @(
    @{ id = "musicaldown"; lang = "multi"; version = "1.0.0" },
    @{ id = "snapsave_twitter"; lang = "multi"; version = "1.0.0" },
    @{ id = "snapsave_instagram"; lang = "multi"; version = "1.0.0" }
)

# ── Filter by requested extensions ───────────────────────────────────────────
if ($Extensions -ne "") {
    $requested = $Extensions -split "," | ForEach-Object { $_.Trim() }
    $AllExtensions = $AllExtensions | Where-Object { $requested -contains $_.id }
}

# ── Resolve catalog apk directory ─────────────────────────────────────────────
$CatalogRoot = if ($CatalogPath -ne "") { $CatalogPath } else { $PSScriptRoot }
$CatalogApkDir = Join-Path $CatalogRoot "apk"
if (-not (Test-Path $CatalogApkDir)) {
    New-Item -ItemType Directory -Path $CatalogApkDir | Out-Null
}

Ensure-LocalProperties -ProjectRoot $PSScriptRoot

# ── Build loop ────────────────────────────────────────────────────────────────
$Success = @()
$Failed  = @()

foreach ($ext in $AllExtensions) {
    $id      = $ext.id
    $lang    = $ext.lang
    $version = $ext.version
    $extDir  = Join-Path $PSScriptRoot "extensions\$id"

    Write-Host "`n--- Building $id (lang=$lang v$version) ---" -ForegroundColor Cyan

    if (-not (Test-Path $extDir)) {
        Write-Warning "Extension directory not found: $extDir - skipping."
        $Failed += $id
        continue
    }

    try {
        # Build using root-level Gradle wrapper from Torikomi-Source
        $rootGradlew = Join-Path $PSScriptRoot "gradlew.bat"
        
        if (-not (Test-Path $rootGradlew)) {
            throw "Gradle wrapper not found at $rootGradlew. Copy from torikomi-kotlin/."
        }
        
        # Build from root, specifying the extension module
        $modulePath = "extensions:$id"
        & $rootGradlew -p $PSScriptRoot ":$modulePath`:assembleRelease"
        $gradleExitCode = $LASTEXITCODE
        
        if ($gradleExitCode -ne 0) {
            Write-Warning "  Gradle build exited with code $gradleExitCode, checking outputs anyway..."
        }

        # Find generated APK (look in extension's build output)
        $outputDirs = @(
            (Join-Path $extDir "android\app\build\outputs\apk\release"),
            (Join-Path $extDir "app\build\outputs\apk\release"),
            (Join-Path $extDir "build\outputs\apk\release")
        ) | Where-Object { Test-Path $_ }

        $apkCandidates = @()
        foreach ($dir in $outputDirs) {
            $apkCandidates += Get-ChildItem -Path $dir -Filter "*.apk" -File -ErrorAction SilentlyContinue
        }
        
        if (-not $apkCandidates -or $apkCandidates.Count -eq 0) {
            throw "No APK found in $extDir (gradle exit code: $gradleExitCode)"
        }

        # Use the release APK
        $src = ($apkCandidates | 
            Where-Object { $_.Name -like "*release*.apk" } |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1).FullName
        
        if (-not $src) {
            $src = ($apkCandidates | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
        }

        $apkSize = [math]::Round((Get-Item $src).Length / 1KB, 2)
        Write-Host "  Built: $(Split-Path $src -Leaf) ($apkSize KB)" -ForegroundColor Green

        $apkName = "torikomi-$lang.$id-v$version.apk"

        $dst = Join-Path $CatalogApkDir $apkName
        Copy-Item -Path $src -Destination $dst -Force
        Write-Host "  OK Copied -> $dst" -ForegroundColor Green

        $Success += $id
    } catch {
        Write-Warning "  FAILED ${id}: $_"
        $Failed += $id
    }
}

# ── Summary ───────────────────────────────────────────────────────────────────
$successList = $Success -join ", "
$failedList  = $Failed  -join ", "
Write-Host "`n--- Build Summary ---" -ForegroundColor Yellow
Write-Host "  Succeeded ($($Success.Count)): $successList" -ForegroundColor Green
if ($Failed.Count -gt 0) {
    Write-Host "  Failed    ($($Failed.Count)): $failedList" -ForegroundColor Red
    exit 1
}
Write-Host ""
