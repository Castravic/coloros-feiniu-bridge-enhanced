# Gallery Stat Failure Fallback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore real Feiniu cloud albums when Gallery's `getGalleryStat` call fails, using cached `ALL_PROJECT` counts when available and direct real-album pagination otherwise.

**Architecture:** Add a focused Xposed compatibility component that recognizes the matching Feiniu provider/cache/stat DTO triplet for Gallery 16.40.8 and 16.40.13. It hooks the statistic method and album-list method by verified reflection shape, keeps a process-local statless-device set, and delegates all authentication, connection, photo, and real-album requests to Gallery's original implementation.

**Tech Stack:** Kotlin 2.0.20, legacy Xposed API 82, Android Gradle Plugin 8.5.2, JDK 17, Android SDK/build-tools 35.0.0, PowerShell static verification.

## Global Constraints

- Do not enumerate every Feiniu cloud photo to reconstruct counts.
- Do not fabricate an `n9q`/`y8q` statistic when the local `ALL_PROJECT` cache is absent.
- Do not return an empty success for a connection or service failure.
- Do not bypass authentication or permission checks; direct fallback still calls Gallery's original `H()` and `F()` methods.
- Do not activate on `getGalleryStat timeout`, `getGalleryPhotos`, `getAlbumList`, or `No connection` failures.
- Keep `erq`, `in80`, and `op80` token-prefix compatibility intact.
- Logs must not contain tokens, device identifiers, NAS addresses, album names/IDs, photo identifiers, or filenames.
- The statless marker remains process-local and is retried after Gallery restarts.
- The Android build uses JDK 17, Android SDK 35, build-tools 35.0.0, and Gradle 8.7.

---

## File Structure

- Create `app/src/main/java/io/github/colorosfeiniu/bridge/GalleryStatFallback.kt`: reflection-shape discovery, failure classification, process-local fallback state, Xposed hooks, and bounded privacy-safe logging.
- Create `scripts/verify-gallery-stat-fallback.ps1`: static regression test for both Gallery variants, eligibility boundaries, direct pagination mapping, and forbidden diagnostic data.
- Modify `app/src/main/java/io/github/colorosfeiniu/bridge/FeiniuBridgeHook.kt`: install the new component from the Gallery package entry point while retaining the existing token and backup compatibility hooks.
- Modify `scripts/verify-gallery-compat.ps1`: assert that both known provider/cache/stat triplets remain present in the fallback source.
- Keep `scripts/verify-token-decryption-diagnostics.ps1`: verify that the already-added token diagnostics remain bounded and secret-free.

---

### Task 1: Add a failing static contract test

**Files:**
- Create: `scripts/verify-gallery-stat-fallback.ps1`
- Test: `scripts/verify-gallery-stat-fallback.ps1`

**Interfaces:**
- Consumes: source text from `GalleryStatFallback.kt` and `FeiniuBridgeHook.kt`.
- Produces: a zero exit code only when both version variants, hook shapes, failure guard, direct pagination, and privacy constraints are present.

- [ ] **Step 1: Write the failing verification script**

```powershell
param(
    [string]$FallbackFile,
    [string]$EntryPointFile
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot

if ([string]::IsNullOrWhiteSpace($FallbackFile)) {
    $FallbackFile = Join-Path $repo 'app\src\main\java\io\github\colorosfeiniu\bridge\GalleryStatFallback.kt'
}
if ([string]::IsNullOrWhiteSpace($EntryPointFile)) {
    $EntryPointFile = Join-Path $repo 'app\src\main\java\io\github\colorosfeiniu\bridge\FeiniuBridgeHook.kt'
}

if (-not (Test-Path -LiteralPath $FallbackFile)) {
    throw "Missing fallback source: $FallbackFile"
}
if (-not (Test-Path -LiteralPath $EntryPointFile)) {
    throw "Missing entry point source: $EntryPointFile"
}

$source = Get-Content -Raw -LiteralPath $FallbackFile
$entryPoint = Get-Content -Raw -LiteralPath $EntryPointFile

$requiredPatterns = @(
    'GalleryVariant\("com\.oplus\.aiunit\.vision\.z0g", "com\.oplus\.aiunit\.vision\.b6q", "com\.oplus\.aiunit\.vision\.y8q"\)',
    'GalleryVariant\("com\.oplus\.aiunit\.vision\.n1g", "com\.oplus\.aiunit\.vision\.q6q", "com\.oplus\.aiunit\.vision\.n9q"\)',
    'private const val STAT_METHOD = "J"',
    'private const val ALBUMS_METHOD = "l"',
    'private const val CONNECTION_METHOD = "H"',
    'private const val REAL_ALBUMS_METHOD = "F"',
    'message\.startsWith\(STAT_FAILURE_PREFIX\)',
    'if \(message\.startsWith\(STAT_TIMEOUT_PREFIX\)\) return false',
    'statlessDevices\.add\(deviceId\)',
    'statlessDevices\.contains\(deviceId\)',
    'invokeRealAlbums\(param\.thisObject, deviceId, limit, offset\)',
    'gallery stat fallback used source=local-cache photos=',
    'gallery stat fallback cache unavailable; enabling real-albums mode',
    'gallery stat fallback real-albums result offset=',
    'MAX_DIAGNOSTIC_EVENTS'
)

foreach ($pattern in $requiredPatterns) {
    if ($source -notmatch $pattern) {
        throw "Missing gallery stat fallback source pattern: $pattern"
    }
}

if ($entryPoint -notmatch 'GalleryStatFallback\.install\(lpparam\.classLoader\)') {
    throw 'Gallery package entry point does not install the stat fallback'
}

$forbiddenPatterns = @(
    'log\([^\r\n]*deviceId',
    'log\([^\r\n]*token',
    'log\([^\r\n]*(albumName|albumId|photoId|fileName)',
    'gallery stat fallback[^\r\n]*param\.args'
)
foreach ($pattern in $forbiddenPatterns) {
    if ($source -match $pattern) {
        throw "Gallery stat fallback may expose sensitive data: $pattern"
    }
}

Write-Host 'Gallery stat fallback verified.'
```

- [ ] **Step 2: Run the new test and verify the red state**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/verify-gallery-stat-fallback.ps1
```

Expected: FAIL with `Missing fallback source: ...GalleryStatFallback.kt`.

- [ ] **Step 3: Commit the red contract test**

```powershell
git add -- scripts/verify-gallery-stat-fallback.ps1
git commit -m "test: define gallery stat fallback contract"
```

Expected: one commit containing only the new verification script.

---

### Task 2: Implement the version-aware fallback component

**Files:**
- Create: `app/src/main/java/io/github/colorosfeiniu/bridge/GalleryStatFallback.kt`
- Test: `scripts/verify-gallery-stat-fallback.ps1`

**Interfaces:**
- Consumes: `ClassLoader`; provider methods `J(connection,String): stat`, `l(Int,Int,String): List`, `H(String,Boolean): connection`, `F(connection,String,Int,Int): List`; cache method `f(String): stat`.
- Produces: `GalleryStatFallback.install(classLoader: ClassLoader)`; Xposed hooks that replace only eligible statistic failures or perform direct real-album pagination.

- [ ] **Step 1: Add variant and method-shape discovery**

Create `GalleryStatFallback.kt` with the following discovery boundary:

```kotlin
package io.github.colorosfeiniu.bridge

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

internal object GalleryStatFallback {
    fun install(classLoader: ClassLoader) {
        val installed = VARIANTS.mapNotNull { variant ->
            installVariant(classLoader, variant)
        }
        when {
            installed.isEmpty() -> log("gallery stat fallback unavailable")
            else -> log("gallery stat fallback installed variants=${installed.size}")
        }
    }

    private fun installVariant(
        classLoader: ClassLoader,
        variant: GalleryVariant,
    ): InstalledVariant? = runCatching {
        val providerClass = Class.forName(variant.providerClass, false, classLoader)
        val cacheClass = Class.forName(variant.cacheClass, false, classLoader)
        val statClass = Class.forName(variant.statClass, false, classLoader)

        val statMethod = providerClass.declaredMethods.single { method ->
            Modifier.isStatic(method.modifiers) &&
                method.name == STAT_METHOD &&
                method.returnType == statClass &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[1] == String::class.java
        }.accessible()
        val albumsMethod = providerClass.declaredMethods.single { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.name == ALBUMS_METHOD &&
                List::class.java.isAssignableFrom(method.returnType) &&
                method.parameterTypes.contentEquals(
                    arrayOf(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java),
                )
        }.accessible()
        val connectionMethod = providerClass.declaredMethods.single { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.name == CONNECTION_METHOD &&
                method.returnType != Void.TYPE &&
                method.parameterTypes.contentEquals(
                    arrayOf(String::class.java, Boolean::class.javaPrimitiveType),
                )
        }.accessible()
        val realAlbumsMethod = providerClass.declaredMethods.single { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.name == REAL_ALBUMS_METHOD &&
                List::class.java.isAssignableFrom(method.returnType) &&
                method.parameterTypes.contentEquals(
                    arrayOf(
                        connectionMethod.returnType,
                        String::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    ),
                )
        }.accessible()
        val cacheMethod = cacheClass.declaredMethods.single { method ->
            Modifier.isStatic(method.modifiers) &&
                method.name == CACHE_METHOD &&
                method.returnType == statClass &&
                method.parameterTypes.contentEquals(arrayOf(String::class.java))
        }.accessible()
        val photoCountField = statClass.getDeclaredField(PHOTO_COUNT_FIELD).apply { isAccessible = true }
        val videoCountField = statClass.getDeclaredField(VIDEO_COUNT_FIELD).apply { isAccessible = true }
        require(photoCountField.type == Int::class.javaPrimitiveType)
        require(videoCountField.type == Int::class.javaPrimitiveType)

        InstalledVariant(
            cacheMethod = cacheMethod,
            connectionMethod = connectionMethod,
            realAlbumsMethod = realAlbumsMethod,
            photoCount = { value -> photoCountField.getInt(value) },
            videoCount = { value -> videoCountField.getInt(value) },
        ).also { installed ->
            XposedBridge.hookMethod(statMethod, StatHook(installed))
            XposedBridge.hookMethod(albumsMethod, AlbumsHook(installed))
        }
    }.onFailure { error ->
        if (error !is ClassNotFoundException) {
            logBounded("gallery stat fallback failed stage=install type=${error.javaClass.simpleName}")
        }
    }.getOrNull()

    private fun Method.accessible(): Method = apply { isAccessible = true }

    private data class GalleryVariant(
        val providerClass: String,
        val cacheClass: String,
        val statClass: String,
    )

    private data class InstalledVariant(
        val cacheMethod: Method,
        val connectionMethod: Method,
        val realAlbumsMethod: Method,
        val photoCount: (Any) -> Int,
        val videoCount: (Any) -> Int,
        val statlessDevices: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    )
```

- [ ] **Step 2: Add strict failure classification and cached-stat replacement**

Append the statistic hook and classifier:

```kotlin
    private class StatHook(
        private val variant: InstalledVariant,
    ) : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val original = param.throwable?.takeIf(::isEligibleStatFailure) ?: return
            val deviceId = param.args.getOrNull(1) as? String ?: return
            val cachedStat = runCatching { variant.cacheMethod.invoke(null, deviceId) }
                .onFailure { error ->
                    logBounded(
                        "gallery stat fallback failed stage=cache type=${unwrap(error).javaClass.simpleName}",
                    )
                }
                .getOrNull()

            val photos = cachedStat?.let(variant.photoCount)
            val videos = cachedStat?.let(variant.videoCount)
            if (cachedStat == null || photos == null || videos == null || photos + videos <= 0) {
                variant.statlessDevices.add(deviceId)
                logBounded("gallery stat fallback cache unavailable; enabling real-albums mode")
                param.throwable = original
                return
            }

            variant.statlessDevices.remove(deviceId)
            param.result = cachedStat
            logBounded(
                "gallery stat fallback used source=local-cache photos=$photos videos=$videos",
            )
        }
    }

    private fun isEligibleStatFailure(error: Throwable): Boolean {
        var cursor: Throwable? = error
        repeat(MAX_CAUSE_DEPTH) {
            val message = cursor?.message.orEmpty()
            if (message.startsWith(STAT_TIMEOUT_PREFIX)) return false
            if (message.startsWith(STAT_FAILURE_PREFIX)) return true
            cursor = cursor?.cause
        }
        return false
    }
```

The callback-generated `IOException` in both tested Gallery versions intentionally drops the raw gRPC error object and retains only `getGalleryStat failed for device:`. This classifier therefore distinguishes callback failure from timeout and from every other service method; authentication and permission errors are not bypassed because the subsequent original `getGalleryPhotos` or `getAlbumList` call must still succeed.

- [ ] **Step 3: Add first-failure and persistent statless album handling**

Append the albums hook and reflection helper:

```kotlin
    private class AlbumsHook(
        private val variant: InstalledVariant,
    ) : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val deviceId = param.args.getOrNull(2) as? String ?: return
            if (!variant.statlessDevices.contains(deviceId)) return

            val offset = param.args[0] as Int
            val limit = param.args[1] as Int
            runCatching { invokeRealAlbums(param.thisObject, deviceId, limit, offset) }
                .onSuccess { result ->
                    param.result = result
                    logRealAlbums(offset, limit, result)
                }
                .onFailure { error ->
                    param.throwable = unwrap(error)
                    logBounded(
                        "gallery stat fallback failed stage=albums type=${unwrap(error).javaClass.simpleName}",
                    )
                }
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            val original = param.throwable?.takeIf(::isEligibleStatFailure) ?: return
            val deviceId = param.args.getOrNull(2) as? String ?: return
            val offset = param.args[0] as Int
            val limit = param.args[1] as Int
            variant.statlessDevices.add(deviceId)

            runCatching { invokeRealAlbums(param.thisObject, deviceId, limit, offset) }
                .onSuccess { result ->
                    param.result = result
                    logRealAlbums(offset, limit, result)
                }
                .onFailure { error ->
                    param.throwable = original
                    logBounded(
                        "gallery stat fallback failed stage=albums type=${unwrap(error).javaClass.simpleName}",
                    )
                }
        }

        private fun invokeRealAlbums(
            provider: Any,
            deviceId: String,
            limit: Int,
            offset: Int,
        ): List<*> {
            val connection = variant.connectionMethod.invoke(provider, deviceId, false)
                ?: throw IllegalStateException("connection unavailable")
            val result = variant.realAlbumsMethod.invoke(
                provider,
                connection,
                deviceId,
                limit,
                offset,
            )
            return result as? List<*>
                ?: throw IllegalStateException("real albums result is not a list")
        }
    }

    private fun logRealAlbums(offset: Int, limit: Int, result: List<*>) {
        logBounded(
            "gallery stat fallback real-albums result offset=$offset limit=$limit count=${result.size}",
        )
    }

    private fun unwrap(error: Throwable): Throwable =
        (error as? InvocationTargetException)?.targetException ?: error
```

- [ ] **Step 4: Add both version triplets and bounded diagnostics**

Complete the object with:

```kotlin
    private fun logBounded(message: String) {
        val shouldLog = synchronized(logLock) {
            if (loggedEvents >= MAX_DIAGNOSTIC_EVENTS) {
                false
            } else {
                loggedEvents += 1
                true
            }
        }
        if (shouldLog) log(message)
    }

    private fun log(message: String) {
        XposedBridge.log("ColorOSFeiniuBridge: $message")
    }

    private const val STAT_METHOD = "J"
    private const val ALBUMS_METHOD = "l"
    private const val CONNECTION_METHOD = "H"
    private const val REAL_ALBUMS_METHOD = "F"
    private const val CACHE_METHOD = "f"
    private const val PHOTO_COUNT_FIELD = "a"
    private const val VIDEO_COUNT_FIELD = "b"
    private const val STAT_FAILURE_PREFIX = "getGalleryStat failed for device:"
    private const val STAT_TIMEOUT_PREFIX = "getGalleryStat timeout for device:"
    private const val MAX_CAUSE_DEPTH = 8
    private const val MAX_DIAGNOSTIC_EVENTS = 40

    private val VARIANTS = listOf(
        GalleryVariant("com.oplus.aiunit.vision.z0g", "com.oplus.aiunit.vision.b6q", "com.oplus.aiunit.vision.y8q"),
        GalleryVariant("com.oplus.aiunit.vision.n1g", "com.oplus.aiunit.vision.q6q", "com.oplus.aiunit.vision.n9q"),
    )
    private val logLock = Any()
    private var loggedEvents = 0
}
```

- [ ] **Step 5: Run the static contract and confirm only entry-point wiring remains red**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/verify-gallery-stat-fallback.ps1
```

Expected: FAIL with `Gallery package entry point does not install the stat fallback`; all fallback-source assertions have passed before that check.

- [ ] **Step 6: Compile the new component before entry-point wiring**

Run:

```powershell
$env:JAVA_HOME = 'C:\Users\ZRhans\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :app:compileDebugKotlin --no-daemon
```

Expected: `BUILD SUCCESSFUL`. Fix only type/reflection errors in `GalleryStatFallback.kt`; do not wire or broaden the hook to make compilation pass.

- [ ] **Step 7: Commit the focused component**

```powershell
git add -- app/src/main/java/io/github/colorosfeiniu/bridge/GalleryStatFallback.kt
git commit -m "feat: add gallery stat fallback component"
```

Expected: one commit containing only `GalleryStatFallback.kt`; the red contract test remains a separate earlier commit.

---

### Task 3: Wire, verify, build, and package the test APK

**Files:**
- Modify: `app/src/main/java/io/github/colorosfeiniu/bridge/FeiniuBridgeHook.kt:23-29`
- Modify: `scripts/verify-gallery-compat.ps1`
- Test: `scripts/verify-gallery-stat-fallback.ps1`
- Test: `scripts/verify-gallery-compat.ps1`
- Test: `scripts/verify-token-decryption-diagnostics.ps1`
- Output: `app/build/outputs/apk/debug/coloros-feiniu-bridge-16.40-gallery-stat-fallback-debug.apk`

**Interfaces:**
- Consumes: `GalleryStatFallback.install(ClassLoader)` from Task 2 and the existing `XC_LoadPackage.LoadPackageParam` entry point.
- Produces: an installable, signed debug APK containing both Gallery-version variants and the existing `op80` token compatibility.

- [ ] **Step 1: Wire the fallback at package load**

Insert the call immediately after `installPrefixFallback(lpparam)`:

```kotlin
        installPrefixFallback(lpparam)
        GalleryStatFallback.install(lpparam.classLoader)
        installBackupPauseDiagnostics(lpparam)
```

- [ ] **Step 2: Extend the compatibility verifier for both provider triplets**

Add an optional `FallbackFile` parameter/default and these assertions to `scripts/verify-gallery-compat.ps1`:

```powershell
param(
    [string]$OldSources,
    [string]$NewSources,
    [string]$HookFile,
    [string]$FallbackFile,
    [string]$CurrentDex,
    [string]$Dexdump
)

if ([string]::IsNullOrWhiteSpace($FallbackFile)) {
    $FallbackFile = Join-Path $repo 'app\src\main\java\io\github\colorosfeiniu\bridge\GalleryStatFallback.kt'
}

Assert-FileContains $FallbackFile 'com\.oplus\.aiunit\.vision\.z0g' 'Fallback no longer covers Gallery 16.40.8 Feiniu provider z0g'
Assert-FileContains $FallbackFile 'com\.oplus\.aiunit\.vision\.b6q' 'Fallback no longer covers Gallery 16.40.8 NAS album cache b6q'
Assert-FileContains $FallbackFile 'com\.oplus\.aiunit\.vision\.y8q' 'Fallback no longer covers Gallery 16.40.8 statistic DTO y8q'
Assert-FileContains $FallbackFile 'com\.oplus\.aiunit\.vision\.n1g' 'Fallback no longer covers Gallery 16.40.13 Feiniu provider n1g'
Assert-FileContains $FallbackFile 'com\.oplus\.aiunit\.vision\.q6q' 'Fallback no longer covers Gallery 16.40.13 NAS album cache q6q'
Assert-FileContains $FallbackFile 'com\.oplus\.aiunit\.vision\.n9q' 'Fallback no longer covers Gallery 16.40.13 statistic DTO n9q'
```

Keep the existing `op80` DEX assertions unchanged.

- [ ] **Step 3: Run the fallback contract and verify green**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/verify-gallery-stat-fallback.ps1
```

Expected: `Gallery stat fallback verified.`

- [ ] **Step 4: Run compatibility and privacy regression scripts**

Run:

```powershell
$work = 'C:\Users\ZRhans\Documents\Codex\2026-06-18\files-mentioned-by-the-user-com\work'
$dexdump = 'C:\Users\ZRhans\AppData\Local\Android\Sdk\build-tools\35.0.0\dexdump.exe'
powershell -ExecutionPolicy Bypass -File scripts/verify-gallery-compat.ps1 `
    -OldSources (Join-Path $work 'gallery-jadx\sources') `
    -NewSources (Join-Path $work 'gallery-16.40.8-jadx\sources') `
    -CurrentDex '.analysis\apk\classes17.dex' `
    -Dexdump $dexdump
powershell -ExecutionPolicy Bypass -File scripts/verify-token-decryption-diagnostics.ps1
```

Expected:

```text
Gallery compatibility hook candidates verified.
Token decryption diagnostics verified.
```

- [ ] **Step 5: Build the debug APK**

Run:

```powershell
$env:JAVA_HOME = 'C:\Users\ZRhans\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat clean :app:assembleDebug --no-daemon
```

Expected: `BUILD SUCCESSFUL` and `app\build\outputs\apk\debug\app-debug.apk` exists.

- [ ] **Step 6: Copy, verify signature, and hash the test artifact**

Run:

```powershell
$sourceApk = 'app\build\outputs\apk\debug\app-debug.apk'
$testApk = 'app\build\outputs\apk\debug\coloros-feiniu-bridge-16.40-gallery-stat-fallback-debug.apk'
$apksigner = 'C:\Users\ZRhans\AppData\Local\Android\Sdk\build-tools\35.0.0\apksigner.bat'
Copy-Item -LiteralPath $sourceApk -Destination $testApk -Force
& $apksigner verify --verbose $testApk
Get-FileHash -Algorithm SHA256 -LiteralPath $testApk
```

Expected: signature verification reports `Verified`, and SHA-256 is printed for handoff.

- [ ] **Step 7: Review the exact diff and commit implementation files only**

Run:

```powershell
git diff --check
git diff -- app/src/main/java/io/github/colorosfeiniu/bridge/FeiniuBridgeHook.kt `
    app/src/main/java/io/github/colorosfeiniu/bridge/GalleryStatFallback.kt `
    scripts/verify-gallery-compat.ps1 `
    scripts/verify-gallery-stat-fallback.ps1 `
    scripts/verify-token-decryption-diagnostics.ps1
git status --short
```

Confirm `.analysis/` and `.learnings/` are not staged, then run:

```powershell
git add -- app/src/main/java/io/github/colorosfeiniu/bridge/FeiniuBridgeHook.kt `
    scripts/verify-gallery-compat.ps1 `
    scripts/verify-token-decryption-diagnostics.ps1
git commit -m "feat: restore gallery albums when Feiniu stats fail"
```

Expected: implementation and already-validated `op80` diagnostics are committed; `.analysis/` and `.learnings/` remain untracked local evidence.

- [ ] **Step 8: Perform device validation after user installs the APK**

Ask the user to install the test APK, force-stop Gallery, reopen the private-cloud collection, and return the LSPosed log ZIP only if albums still do not appear. Success requires one of:

```text
gallery stat fallback used source=local-cache photos=N videos=N
gallery stat fallback real-albums result offset=0 limit=N count=N
```

and visible Feiniu real albums/photos in Gallery. `gallery stat fallback installed` alone is not success.
