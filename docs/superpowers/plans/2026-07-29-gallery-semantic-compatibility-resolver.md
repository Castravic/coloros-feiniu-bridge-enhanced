# Gallery Semantic Compatibility Resolver Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a fail-closed semantic resolver so ordinary ColorOS Gallery obfuscation renames no longer require a new bridge release for private-cloud authentication and gallery listing.

**Architecture:** Keep the current known-name mappings as a zero-scan fast path, then resolve only missing Token or Gallery groups from a fingerprinted descriptor cache or a DexKit semantic scan after `Application.attach(Context)`. Every source produces immutable descriptors that pass the same reflection validator before hooks are installed; each group succeeds or fails independently and ambiguous results are rejected.

**Tech Stack:** Kotlin 2.0.20, Android application module (minSdk 26 / targetSdk 35), legacy Xposed API 82, DexKit 2.2.0, JUnit 4.13.2, AndroidX Test runner 1.7.0 and ext.junit 1.3.0, Gradle 8.7, JDK 17.

## Global Constraints

- Only run in package `com.coloros.gallery3d`.
- Keep all existing known class mappings and their behavior as the fast path.
- Resolve Token and Gallery groups independently; never persist or install a partial group.
- Install a semantic result only when exactly one complete candidate group remains.
- Pin DexKit exactly to `org.luckypray:dexkit:2.2.0`; do not use `DexKitCacheBridge`.
- Preserve `android:minSdkVersion=26`, `targetSdk=35`, legacy Xposed API 82, and `android:extractNativeLibs="false"`.
- Do not semantically resolve backup temperature, pause state/text, mobile-data controls, or `ktc0` private-LAN TLS in this release.
- Do not install global hooks on `TrustManager`, `HostnameVerifier`, `Cipher`, or `CryptoEngManager`.
- Cache descriptors only. Never cache or log Token contents, device IDs, NAS addresses, credentials, or response payloads.
- Known mappings must not open DexKit. Cache validation target is under 100 ms; cold semantic scan target is under 5 seconds.
- Permit one resolver scan per Gallery process and memoize the failure result for the rest of that process.
- Keep local vendor APKs, extracted DEX, and JADX output ignored and out of commits.
- Publish the completed resolver as versionCode `14`, versionName `0.3.6`; do not bump before the final verification task.
- Run production work test-first and commit after every task with only that task's files staged.

## File Structure

### New production files

- `app/src/main/java/io/github/colorosfeiniu/bridge/resolver/ConnectionHookRefs.kt` — immutable method/field descriptors and complete Token/Gallery group models.
- `app/src/main/java/io/github/colorosfeiniu/bridge/resolver/ConnectionHookValidator.kt` — loads descriptors through the Gallery class loader and enforces method/field/type relationships.
- `app/src/main/java/io/github/colorosfeiniu/bridge/resolver/KnownConnectionResolver.kt` — converts the existing candidate tables into independently validated known groups.
- `app/src/main/java/io/github/colorosfeiniu/bridge/resolver/GalleryFingerprint.kt` — canonical Gallery package/APK/split identity and schema key.
- `app/src/main/java/io/github/colorosfeiniu/bridge/resolver/ConnectionResolutionCache.kt` — group-specific descriptor persistence with fingerprint invalidation.
- `app/src/main/java/io/github/colorosfeiniu/bridge/resolver/DexSemanticIndex.kt` — small library-independent semantic-record interface used by pure resolvers.
- `app/src/main/java/io/github/colorosfeiniu/bridge/resolver/DexKitSemanticIndex.kt` — DexKit 2.2.0 adapter and bounded queries.
- `app/src/main/java/io/github/colorosfeiniu/bridge/resolver/TokenSemanticResolver.kt` — unique Token prefix/decrypt call-chain matcher.
- `app/src/main/java/io/github/colorosfeiniu/bridge/resolver/GallerySemanticResolver.kt` — unique Provider/Cache/DTO closed-graph matcher.
- `app/src/main/java/io/github/colorosfeiniu/bridge/resolver/ConnectionResolutionCoordinator.kt` — known/cache/semantic source ordering and group merging.
- `app/src/main/java/io/github/colorosfeiniu/bridge/resolver/ConnectionResolutionBootstrap.kt` — one-shot `Application.attach` bootstrap, fingerprinting, timing, and process memoization.
- `app/src/main/java/io/github/colorosfeiniu/bridge/resolver/ConnectionHookInstaller.kt` — final validation gate and delegation to existing Xposed hooks.
- `app/src/main/java/io/github/colorosfeiniu/bridge/resolver/ResolverLog.kt` — bounded structured logs with a sensitive-key rejection guard.

### Modified production/build files

- `app/src/main/java/io/github/colorosfeiniu/bridge/FeiniuBridgeHook.kt` — delegate connection hook discovery/installation to the resolver bootstrap; leave unrelated hooks unchanged.
- `app/src/main/java/io/github/colorosfeiniu/bridge/GalleryStatFallback.kt` — accept validated reflected methods/fields instead of resolving hard-coded variants itself.
- `app/build.gradle.kts` — pin DexKit and AndroidX instrumentation dependencies.
- `README.md` and `CHANGELOG.md` — document semantic fallback, cache behavior, size/scan trade-off, and safe failure.
- `scripts/verify-semantic-resolver.ps1` — repeatable unit/build/APK/native-library verification.

### Test files

- JVM tests under `app/src/test/java/io/github/colorosfeiniu/bridge/resolver/` mirror each pure component.
- Instrumentation tests and renamed/ambiguous fixtures live under `app/src/androidTest/java/io/github/colorosfeiniu/bridge/resolver/`.
- Local APK verification uses ignored inputs under `.analysis/gallery-apks/` and writes ignored reports under `.analysis/resolver-reports/`.

---

### Task 1: Immutable descriptor model

**Files:**
- Create: `app/src/main/java/io/github/colorosfeiniu/bridge/resolver/ConnectionHookRefs.kt`
- Create: `app/src/test/java/io/github/colorosfeiniu/bridge/resolver/ConnectionHookRefsTest.kt`

**Interfaces:**
- Consumes: no project-specific types.
- Produces:
  - `enum class ResolutionSource { KNOWN, CACHE, SEMANTIC }`
  - `data class MethodRef(...)`
  - `data class FieldRef(...)`
  - `data class TokenHookRefs(...)`
  - `data class GalleryHookRefs(...)`
  - `data class ResolvedConnectionHooks(...)`

- [ ] **Step 1: Write the failing invariants test**

```kotlin
package io.github.colorosfeiniu.bridge.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConnectionHookRefsTest {
    private val prefix = MethodRef(
        descriptor = "Lx/T;->p()Ljava/lang/String;",
        className = "x.T",
        name = "p",
        returnTypeName = "java.lang.String",
        parameterTypeNames = emptyList(),
        isStatic = false,
    )
    private val decrypt = MethodRef(
        descriptor = "Lx/T;->d(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
        className = "x.T",
        name = "d",
        returnTypeName = "java.lang.String",
        parameterTypeNames = listOf("java.lang.String", "java.lang.String"),
        isStatic = false,
    )

    @Test fun `resolved hooks require at least one complete group`() {
        assertThrows(IllegalArgumentException::class.java) {
            ResolvedConnectionHooks(token = null, gallery = null)
        }
    }

    @Test fun `token group preserves immutable descriptors and source`() {
        val token = TokenHookRefs(prefix, decrypt, ResolutionSource.SEMANTIC)
        assertEquals("x.T", token.prefix.className)
        assertEquals(ResolutionSource.SEMANTIC, token.source)
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```powershell
$env:JAVA_HOME='C:\Users\ZRhans\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2'
& 'C:\Users\ZRhans\Documents\Codex\2026-06-18\files-mentioned-by-the-user-com\work\gradle\gradle-8.7\bin\gradle.bat' :app:testDebugUnitTest --tests '*ConnectionHookRefsTest'
```

Expected: compilation fails because `MethodRef`, `TokenHookRefs`, and `ResolvedConnectionHooks` do not exist.

- [ ] **Step 3: Add the minimal immutable model**

```kotlin
package io.github.colorosfeiniu.bridge.resolver

enum class ResolutionSource { KNOWN, CACHE, SEMANTIC }

data class MethodRef(
    val descriptor: String,
    val className: String,
    val name: String,
    val returnTypeName: String,
    val parameterTypeNames: List<String>,
    val isStatic: Boolean,
)

data class FieldRef(
    val descriptor: String,
    val className: String,
    val name: String,
    val typeName: String,
    val isStatic: Boolean,
)

data class TokenHookRefs(
    val prefix: MethodRef,
    val decrypt: MethodRef,
    val source: ResolutionSource,
) {
    init {
        require(prefix.className == decrypt.className)
    }
}

data class GalleryHookRefs(
    val stat: MethodRef,
    val albums: MethodRef,
    val connection: MethodRef,
    val realAlbums: MethodRef,
    val cache: MethodRef,
    val photoCount: FieldRef,
    val videoCount: FieldRef,
    val source: ResolutionSource,
)

data class ResolvedConnectionHooks(
    val token: TokenHookRefs?,
    val gallery: GalleryHookRefs?,
) {
    init {
        require(token != null || gallery != null)
    }
}
```

- [ ] **Step 4: Run the focused test and all existing JVM tests**

Run the command from Step 2, then:

```powershell
& 'C:\Users\ZRhans\Documents\Codex\2026-06-18\files-mentioned-by-the-user-com\work\gradle\gradle-8.7\bin\gradle.bat' :app:testDebugUnitTest
```

Expected: both commands end with `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit only the descriptor model**

```powershell
git add app/src/main/java/io/github/colorosfeiniu/bridge/resolver/ConnectionHookRefs.kt app/src/test/java/io/github/colorosfeiniu/bridge/resolver/ConnectionHookRefsTest.kt
git commit -m "feat: add connection hook descriptor model"
```

---

### Task 2: Reflection validator and known-name fast path

**Files:**
- Create: `app/src/main/java/io/github/colorosfeiniu/bridge/resolver/ConnectionHookValidator.kt`
- Create: `app/src/main/java/io/github/colorosfeiniu/bridge/resolver/KnownConnectionResolver.kt`
- Create: `app/src/test/java/io/github/colorosfeiniu/bridge/resolver/ConnectionHookValidatorTest.kt`
- Create: `app/src/test/java/io/github/colorosfeiniu/bridge/resolver/KnownConnectionResolverTest.kt`
- Create: `app/src/test/java/io/github/colorosfeiniu/bridge/resolver/ResolverFixtures.kt`

**Interfaces:**
- Consumes: `MethodRef`, `FieldRef`, `TokenHookRefs`, `GalleryHookRefs`, `ResolutionSource`.
- Produces:
  - `data class ValidatedTokenHooks(val prefix: Method, val decrypt: Method)`
  - `data class ValidatedGalleryHooks(val stat: Method, val albums: Method, val connection: Method, val realAlbums: Method, val cache: Method, val photoCount: Field, val videoCount: Field)`
  - `ConnectionHookValidator.validateToken(TokenHookRefs, ClassLoader): ValidatedTokenHooks?`
  - `ConnectionHookValidator.validateGallery(GalleryHookRefs, ClassLoader): ValidatedGalleryHooks?`
  - `KnownConnectionResolver.resolve(ClassLoader): KnownGroups`
  - `data class KnownGroups(val token: TokenHookRefs?, val gallery: GalleryHookRefs?)`

- [ ] **Step 1: Add fixtures and failing validator tests**

Create fixture classes whose methods have the exact required shapes:

```kotlin
internal class TokenFixture {
    fun prefix(): String = "prefix"
    fun decrypt(first: String, second: String): String = first + second
}

internal class ProviderFixture {
    fun albums(offset: Int, limit: Int, device: String): List<String> = emptyList()
    fun connection(device: String, cached: Boolean): ConnectionFixture = ConnectionFixture()
    fun realAlbums(
        connection: ConnectionFixture,
        device: String,
        limit: Int,
        offset: Int,
    ): List<String> = emptyList()

    companion object {
        @JvmStatic fun stat(connection: ConnectionFixture, device: String): StatFixture =
            StatFixture()
    }
}
internal class ConnectionFixture
internal class StatFixture {
    @JvmField var photos: Int = 0
    @JvmField var videos: Int = 0
}
internal object CacheFixture {
    @JvmStatic fun get(device: String): StatFixture = StatFixture()
}
```

Test a correct group and one wrong relationship:

```kotlin
@Test fun `gallery validator accepts complete linked signatures`() {
    val validated = ConnectionHookValidator.validateGallery(validGalleryRefs(), javaClass.classLoader!!)
    assertNotNull(validated)
}

@Test fun `gallery validator rejects real albums with wrong connection type`() {
    val invalid = validGalleryRefs().copy(
        realAlbums = validGalleryRefs().realAlbums.copy(
            parameterTypeNames = listOf(
                "java.lang.String", "java.lang.String", "int", "int",
            ),
        ),
    )
    assertNull(ConnectionHookValidator.validateGallery(invalid, javaClass.classLoader!!))
}
```

- [ ] **Step 2: Run focused tests and verify missing validator failures**

Run:

```powershell
& 'C:\Users\ZRhans\Documents\Codex\2026-06-18\files-mentioned-by-the-user-com\work\gradle\gradle-8.7\bin\gradle.bat' :app:testDebugUnitTest --tests '*ConnectionHookValidatorTest' --tests '*KnownConnectionResolverTest'
```

Expected: compilation fails because the validator and known resolver do not exist.

- [ ] **Step 3: Implement exact reflection loading and relationship checks**

Use descriptor metadata to select exactly one declared member, and reject any mismatch:

```kotlin
private fun Method.matches(ref: MethodRef): Boolean =
    declaringClass.name == ref.className &&
        name == ref.name &&
        returnType.name == ref.returnTypeName &&
        parameterTypes.map(Class<*>::getName) == ref.parameterTypeNames &&
        Modifier.isStatic(modifiers) == ref.isStatic

private fun loadMethod(ref: MethodRef, loader: ClassLoader): Method? =
    runCatching {
        Class.forName(ref.className, false, loader)
            .declaredMethods
            .single { it.matches(ref) }
            .apply { isAccessible = true }
    }.getOrNull()
```

`validateToken` must additionally require an instance no-arg `String` prefix, an instance `(String,String)->String` decrypt method, and one declaring class. `validateGallery` must additionally require:

```kotlin
stat.isStatic &&
albums.parameterTypes.map { it.name } == listOf("int", "int", "java.lang.String") &&
connection.parameterTypes.map { it.name } == listOf("java.lang.String", "boolean") &&
connection.returnType != Void.TYPE &&
realAlbums.parameterTypes.map { it.name } ==
    listOf(connection.returnType.name, "java.lang.String", "int", "int") &&
cache.isStatic &&
cache.returnType == stat.returnType &&
photoCount.declaringClass == stat.returnType &&
videoCount.declaringClass == stat.returnType &&
photoCount.type == Int::class.javaPrimitiveType &&
videoCount.type == Int::class.javaPrimitiveType &&
!Modifier.isStatic(photoCount.modifiers) &&
!Modifier.isStatic(videoCount.modifiers)
```

- [ ] **Step 4: Implement known groups with independent validation**

Move the existing names into `KnownConnectionResolver`:

```kotlin
private val tokenClasses = listOf(
    "com.oplus.aiunit.vision.erq",
    "com.oplus.aiunit.vision.in80",
    "com.oplus.aiunit.vision.op80",
    "com.oplus.aiunit.vision.qp80",
)
private const val knownPrefixName = "e"
private const val knownDecryptName = "b"

private val galleryVariants = listOf(
    GalleryNames("com.oplus.aiunit.vision.z0g", "com.oplus.aiunit.vision.b6q", "com.oplus.aiunit.vision.y8q",
        "J", "l", "H", "F", "f", "a", "b"),
    GalleryNames("com.oplus.aiunit.vision.n1g", "com.oplus.aiunit.vision.q6q", "com.oplus.aiunit.vision.n9q",
        "J", "l", "H", "F", "f", "a", "b"),
)
```

Generate `MethodRef`/`FieldRef` values from reflected members, set `ResolutionSource.KNOWN`, and return a group only if `ConnectionHookValidator` accepts it. Token and Gallery resolution must be separate `mapNotNull(...).singleOrNull()` calls so one failed group cannot suppress the other.

- [ ] **Step 5: Add known-path tests**

Inject candidate lists into an internal constructor and prove:

```kotlin
@Test fun `known resolver returns token even when gallery names are absent`() {
    val resolver = KnownConnectionResolver(
        tokenClassNames = listOf(TokenFixture::class.java.name),
        galleryNames = emptyList(),
    )
    val result = resolver.resolve(javaClass.classLoader!!)
    assertNotNull(result.token)
    assertNull(result.gallery)
}

@Test fun `duplicate complete known token groups fail closed`() {
    val resolver = KnownConnectionResolver(
        tokenClassNames = listOf(TokenFixture::class.java.name, SecondTokenFixture::class.java.name),
        galleryNames = emptyList(),
    )
    assertNull(resolver.resolve(javaClass.classLoader!!).token)
}
```

- [ ] **Step 6: Run tests and commit**

Run:

```powershell
& 'C:\Users\ZRhans\Documents\Codex\2026-06-18\files-mentioned-by-the-user-com\work\gradle\gradle-8.7\bin\gradle.bat' :app:testDebugUnitTest --tests '*ConnectionHookValidatorTest' --tests '*KnownConnectionResolverTest'
```

Expected: `BUILD SUCCESSFUL`.

Then:

```powershell
git add app/src/main/java/io/github/colorosfeiniu/bridge/resolver/ConnectionHookValidator.kt app/src/main/java/io/github/colorosfeiniu/bridge/resolver/KnownConnectionResolver.kt app/src/test/java/io/github/colorosfeiniu/bridge/resolver/ConnectionHookValidatorTest.kt app/src/test/java/io/github/colorosfeiniu/bridge/resolver/KnownConnectionResolverTest.kt app/src/test/java/io/github/colorosfeiniu/bridge/resolver/ResolverFixtures.kt
git commit -m "feat: validate known connection hook groups"
```

---

### Task 3: Fingerprint and descriptor cache

**Files:**
- Create: `app/src/main/java/io/github/colorosfeiniu/bridge/resolver/GalleryFingerprint.kt`
- Create: `app/src/main/java/io/github/colorosfeiniu/bridge/resolver/ConnectionResolutionCache.kt`
- Create: `app/src/test/java/io/github/colorosfeiniu/bridge/resolver/GalleryFingerprintTest.kt`
- Create: `app/src/test/java/io/github/colorosfeiniu/bridge/resolver/ConnectionResolutionCacheTest.kt`

**Interfaces:**
- Consumes: complete Token/Gallery descriptor groups and the validator callback.
- Produces:
  - `data class ApkIdentity(val path: String, val length: Long, val lastModified: Long)`
  - `data class GalleryFingerprint(val packageName: String, val versionCode: Long, val lastUpdateTime: Long, val apks: List<ApkIdentity>, val schemaVersion: Int = 1)`
  - `GalleryFingerprint.cacheNamespace(): String`
  - `interface StringStore { fun get(key: String): String?; fun put(key: String, value: String); fun remove(key: String) }`
  - `class SharedPreferencesStringStore`
  - `ConnectionResolutionCache.readToken(...)`, `readGallery(...)`, `writeToken(...)`, `writeGallery(...)`, `invalidateToken(...)`, `invalidateGallery(...)`

- [ ] **Step 1: Write fingerprint invalidation tests**

```kotlin
@Test fun `split metadata changes fingerprint namespace`() {
    val base = GalleryFingerprint(
        packageName = "com.coloros.gallery3d",
        versionCode = 164022,
        lastUpdateTime = 100,
        apks = listOf(ApkIdentity("/base.apk", 10, 20), ApkIdentity("/split.apk", 30, 40)),
    )
    assertNotEquals(
        base.cacheNamespace(),
        base.copy(apks = base.apks.dropLast(1) + ApkIdentity("/split.apk", 31, 40)).cacheNamespace(),
    )
}

@Test fun `apk order does not change fingerprint namespace`() {
    val fingerprint = fixtureFingerprint()
    assertEquals(
        fingerprint.cacheNamespace(),
        fingerprint.copy(apks = fingerprint.apks.reversed()).cacheNamespace(),
    )
}
```

Also cover `versionCode`, `lastUpdateTime`, base APK path, length, mtime, split path, and `schemaVersion` one field at a time.

- [ ] **Step 2: Write cache corruption and group-isolation tests**

Use `FakeStringStore(mutableMapOf())` and test these exact outcomes:

```kotlin
@Test fun `corrupt token cache is removed without deleting gallery group`() {
    store.put(cache.tokenKey(fingerprint), "not-a-valid-record")
    store.put(cache.galleryKey(fingerprint), encodedGallery)

    assertNull(cache.readToken(fingerprint) { true })
    assertNotNull(cache.readGallery(fingerprint) { true })
    assertNull(store.get(cache.tokenKey(fingerprint)))
    assertNotNull(store.get(cache.galleryKey(fingerprint)))
}

@Test fun `validator rejection invalidates only rejected group`() {
    cache.writeToken(fingerprint, tokenRefs)
    assertNull(cache.readToken(fingerprint) { false })
    assertNull(store.get(cache.tokenKey(fingerprint)))
}
```

- [ ] **Step 3: Run tests and verify missing-type failures**

Run:

```powershell
& 'C:\Users\ZRhans\Documents\Codex\2026-06-18\files-mentioned-by-the-user-com\work\gradle\gradle-8.7\bin\gradle.bat' :app:testDebugUnitTest --tests '*GalleryFingerprintTest' --tests '*ConnectionResolutionCacheTest'
```

Expected: compilation fails because the fingerprint and cache classes do not exist.

- [ ] **Step 4: Implement deterministic fingerprinting**

Canonicalize by sorted APK path, include every field and SHA-256 the UTF-8 record:

```kotlin
fun GalleryFingerprint.cacheNamespace(): String {
    val canonical = buildString {
        append("schema=").append(schemaVersion).append('\n')
        append("package=").append(packageName).append('\n')
        append("versionCode=").append(versionCode).append('\n')
        append("lastUpdateTime=").append(lastUpdateTime).append('\n')
        apks.sortedBy(ApkIdentity::path).forEach {
            append("apk=").append(it.path).append('|')
                .append(it.length).append('|').append(it.lastModified).append('\n')
        }
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
```

- [ ] **Step 5: Implement a strict length-prefixed cache codec**

Use `DataOutputStream`/`DataInputStream` plus `java.util.Base64` so JVM tests and Android API 26 share one implementation. Write a magic `0x43464252`, cache format version `1`, group tag (`TOKEN` or `GALLERY`), then every `MethodRef`/`FieldRef` property and source. Decode only when the stream is exhausted exactly; reject negative/list sizes over `16`, wrong tags, wrong versions, and trailing bytes. Cache keys are:

```kotlin
internal fun tokenKey(fingerprint: GalleryFingerprint) =
    "resolver.${fingerprint.cacheNamespace()}.token"
internal fun galleryKey(fingerprint: GalleryFingerprint) =
    "resolver.${fingerprint.cacheNamespace()}.gallery"
```

`readToken` and `readGallery` must decode, replace the stored source with `ResolutionSource.CACHE`, call the supplied validator, and remove only the failing group.

- [ ] **Step 6: Run all cache tests and commit**

Run:

```powershell
& 'C:\Users\ZRhans\Documents\Codex\2026-06-18\files-mentioned-by-the-user-com\work\gradle\gradle-8.7\bin\gradle.bat' :app:testDebugUnitTest --tests '*GalleryFingerprintTest' --tests '*ConnectionResolutionCacheTest'
```

Expected: `BUILD SUCCESSFUL`.

Then:

```powershell
git add app/src/main/java/io/github/colorosfeiniu/bridge/resolver/GalleryFingerprint.kt app/src/main/java/io/github/colorosfeiniu/bridge/resolver/ConnectionResolutionCache.kt app/src/test/java/io/github/colorosfeiniu/bridge/resolver/GalleryFingerprintTest.kt app/src/test/java/io/github/colorosfeiniu/bridge/resolver/ConnectionResolutionCacheTest.kt
git commit -m "feat: cache resolver descriptors by gallery fingerprint"
```

---

### Task 4: DexKit adapter and safe resolver logging

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/io/github/colorosfeiniu/bridge/resolver/DexSemanticIndex.kt`
- Create: `app/src/main/java/io/github/colorosfeiniu/bridge/resolver/DexKitSemanticIndex.kt`
- Create: `app/src/main/java/io/github/colorosfeiniu/bridge/resolver/ResolverLog.kt`
- Create: `app/src/test/java/io/github/colorosfeiniu/bridge/resolver/ResolverLogTest.kt`

**Interfaces:**
- Consumes: DexKit 2.2.0 `DexKitBridge`, `MethodData`, `ClassData`, and `FieldData`.
- Produces:
  - `data class DexMethodRecord(...)`
  - `data class DexFieldRecord(...)`
  - `interface DexSemanticIndex : Closeable`
  - `DexKitSemanticIndex.create(classLoader: ClassLoader): DexKitSemanticIndex`
  - `class ResolverLog(private val sink: (String) -> Unit, private val maxEvents: Int = 40)`

- [ ] **Step 1: Add the pinned dependency and instrumentation runner**

Edit only the relevant blocks in `app/build.gradle.kts`:

```kotlin
defaultConfig {
    applicationId = "io.github.colorosfeiniu.bridge"
    minSdk = 26
    targetSdk = 35
    versionCode = 13
    versionName = "0.3.5"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
}

dependencies {
    compileOnly("de.robv.android.xposed:api:$xposedCompileApiVersion")
    implementation("org.luckypray:dexkit:2.2.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
```

Do not add `useLegacyPackaging`; minSdk 26 and the current uncompressed native-library packaging are compatible with `System.loadLibrary`.

- [ ] **Step 2: Define the library-independent semantic index**

```kotlin
data class DexMethodRecord(
    val descriptor: String,
    val className: String,
    val name: String,
    val returnTypeName: String,
    val parameterTypeNames: List<String>,
    val isStatic: Boolean,
    val usingStrings: Set<String>,
    val invokeDescriptors: Set<String>,
    val usingFieldDescriptors: List<String>,
)

data class DexFieldRecord(
    val descriptor: String,
    val className: String,
    val name: String,
    val typeName: String,
    val isStatic: Boolean,
)

interface DexSemanticIndex : Closeable {
    fun methodsUsingStrings(strings: Set<String>): List<DexMethodRecord>
    fun methodsByPrototype(
        returnTypeName: String,
        parameterTypeNames: List<String>,
        isStatic: Boolean,
    ): List<DexMethodRecord>
    fun method(descriptor: String): DexMethodRecord?
    fun methodsOfClass(className: String): List<DexMethodRecord>
    fun fieldsOfClass(className: String): List<DexFieldRecord>
}
```

- [ ] **Step 3: Write failing log-safety and event-bound tests**

```kotlin
@Test fun `resolver log rejects sensitive field names`() {
    val output = mutableListOf<String>()
    val log = ResolverLog(output::add)
    log.event("semantic", mapOf("token" to "secret"))
    assertTrue(output.single().contains("rejected-sensitive-fields"))
    assertFalse(output.single().contains("secret"))
}

@Test fun `resolver log stops after configured event limit`() {
    val output = mutableListOf<String>()
    val log = ResolverLog(output::add, maxEvents = 2)
    repeat(5) { log.event("scan", mapOf("candidateCount" to it.toString())) }
    assertEquals(2, output.size)
}
```

Sensitive keys, compared case-insensitively, are exactly:

```kotlin
setOf("token", "deviceid", "nas", "address", "credential", "password", "payload", "response")
```

- [ ] **Step 4: Run the focused test and verify it fails**

Run:

```powershell
& 'C:\Users\ZRhans\Documents\Codex\2026-06-18\files-mentioned-by-the-user-com\work\gradle\gradle-8.7\bin\gradle.bat' :app:testDebugUnitTest --tests '*ResolverLogTest'
```

Expected: compilation fails because `ResolverLog` does not exist.

- [ ] **Step 5: Implement the bounded log**

Emit sorted `key=value` metadata and never interpolate unknown values before the key guard:

```kotlin
fun event(stage: String, fields: Map<String, String> = emptyMap()) {
    val accepted = synchronized(lock) {
        if (count >= maxEvents) false else { count += 1; true }
    }
    if (!accepted) return
    if (fields.keys.any { it.lowercase() in sensitiveKeys }) {
        sink("resolver stage=$stage result=rejected-sensitive-fields")
        return
    }
    val suffix = fields.toSortedMap().entries.joinToString(" ") { "${it.key}=${it.value}" }
    sink(listOf("resolver", "stage=$stage", suffix).filter(String::isNotBlank).joinToString(" "))
}
```

- [ ] **Step 6: Implement and compile-check the DexKit adapter**

Load the native library only inside `create` and map DexKit data immediately into pure records:

```kotlin
companion object {
    fun create(classLoader: ClassLoader): DexKitSemanticIndex {
        System.loadLibrary("dexkit")
        return DexKitSemanticIndex(DexKitBridge.create(classLoader, false))
    }
}
```

Use bounded `findMethod` queries anchored by exact strings or an exact prototype rather than `initFullCache`. `methodsUsingStrings` must add one `StringMatcher(anchor, StringMatchType.Equals)` per supplied anchor, so substring matches are rejected. `methodsByPrototype` must set exact return type and ordered parameter types, then post-filter with `Modifier.isStatic(data.modifiers) == isStatic`. Convert `MethodData.descriptor`, `declaredClassName`, `methodName`, `returnTypeName`, `paramTypeNames`, `modifiers`, `usingStrings`, `invokes.map { it.descriptor }`, and `usingFields.map { it.field.descriptor }`. `methodsOfClass` and `fieldsOfClass` use `bridge.getClassData(className)?.methods/fields`. `close()` delegates to `bridge.close()`.

Run:

```powershell
& 'C:\Users\ZRhans\Documents\Codex\2026-06-18\files-mentioned-by-the-user-com\work\gradle\gradle-8.7\bin\gradle.bat' :app:compileDebugKotlin :app:testDebugUnitTest --tests '*ResolverLogTest'
```

Expected: `BUILD SUCCESSFUL`; this compile gate catches any DexKit 2.2.0 API-name mismatch before the semantic logic is written.

- [ ] **Step 7: Commit the adapter**

```powershell
git add app/build.gradle.kts app/src/main/java/io/github/colorosfeiniu/bridge/resolver/DexSemanticIndex.kt app/src/main/java/io/github/colorosfeiniu/bridge/resolver/DexKitSemanticIndex.kt app/src/main/java/io/github/colorosfeiniu/bridge/resolver/ResolverLog.kt app/src/test/java/io/github/colorosfeiniu/bridge/resolver/ResolverLogTest.kt
git commit -m "feat: add bounded DexKit semantic index"
```

---

### Task 5: Unique Token semantic resolver

**Files:**
- Create: `app/src/main/java/io/github/colorosfeiniu/bridge/resolver/TokenSemanticResolver.kt`
- Create: `app/src/test/java/io/github/colorosfeiniu/bridge/resolver/FakeDexSemanticIndex.kt`
- Create: `app/src/test/java/io/github/colorosfeiniu/bridge/resolver/TokenSemanticResolverTest.kt`

**Interfaces:**
- Consumes: `DexSemanticIndex`, `DexMethodRecord`, `TokenHookRefs`.
- Produces: `TokenSemanticResolver.resolve(index: DexSemanticIndex): SemanticResult<TokenHookRefs>`.
- Introduces:

```kotlin
sealed interface SemanticResult<out T> {
    data class Found<T>(val value: T, val candidateCount: Int) : SemanticResult<T>
    data class Missing(val candidateCount: Int = 0) : SemanticResult<Nothing>
    data class Ambiguous(val candidateCount: Int) : SemanticResult<Nothing>
}
```

- [ ] **Step 1: Write the renamed-candidate success test**

Build records with arbitrary names and the real stable evidence:

```kotlin
@Test fun `renamed token class resolves by call chain and strings`() {
    val prefix = method(
        "Lrandom/A7;->z()Ljava/lang/String;",
        "random.A7", "z", "java.lang.String", emptyList(), false,
        strings = setOf("com.oplus.hardware.cryptoeng.CryptoEngManager"),
    )
    val decrypt = method(
        "Lrandom/A7;->q(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
        "random.A7", "q", "java.lang.String",
        listOf("java.lang.String", "java.lang.String"), false,
        strings = setOf("TokenDecryptor", "AES/GCM/NoPadding", "SHA-256"),
        invokes = setOf(prefix.descriptor),
    )
    val result = TokenSemanticResolver().resolve(FakeDexSemanticIndex(prefix, decrypt))
    val found = result as SemanticResult.Found
    assertEquals(prefix.descriptor, found.value.prefix.descriptor)
    assertEquals(decrypt.descriptor, found.value.decrypt.descriptor)
    assertEquals(ResolutionSource.SEMANTIC, found.value.source)
}
```

- [ ] **Step 2: Add strict failure tests**

Add one test per broken hard gate:

- prefix is static;
- prefix has a parameter or non-String return;
- decrypt is static;
- decrypt signature is not `(String,String)->String`;
- prefix is in a different class;
- decrypt does not invoke prefix;
- one of `TokenDecryptor`, `CryptoEngManager`, `AES/GCM/NoPadding`, or `SHA-256` is absent;
- two complete pairs exist.

The last assertion is:

```kotlin
assertEquals(
    SemanticResult.Ambiguous(candidateCount = 2),
    TokenSemanticResolver().resolve(indexWithTwoCompletePairs()),
)
```

- [ ] **Step 3: Run tests and verify resolver is missing**

Run:

```powershell
& 'C:\Users\ZRhans\Documents\Codex\2026-06-18\files-mentioned-by-the-user-com\work\gradle\gradle-8.7\bin\gradle.bat' :app:testDebugUnitTest --tests '*TokenSemanticResolverTest'
```

Expected: compilation fails because `TokenSemanticResolver` and `SemanticResult` do not exist.

- [ ] **Step 4: Implement exact, non-scoring matching**

Start from decrypt methods containing all three decrypt anchors:

```kotlin
val decrypts = index.methodsUsingStrings(
    setOf("TokenDecryptor", "AES/GCM/NoPadding", "SHA-256"),
).filter(::isDecryptShape)

val pairs = decrypts.flatMap { decrypt ->
    decrypt.invokeDescriptors.mapNotNull(index::method)
        .filter { prefix ->
            prefix.className == decrypt.className &&
                !prefix.isStatic &&
                prefix.returnTypeName == "java.lang.String" &&
                prefix.parameterTypeNames.isEmpty() &&
                "com.oplus.hardware.cryptoeng.CryptoEngManager" in prefix.usingStrings
        }
        .map { prefix -> prefix to decrypt }
}.distinctBy { (prefix, decrypt) -> prefix.descriptor to decrypt.descriptor }
```

Return `Missing(0)`, `Found(TokenHookRefs(...), 1)`, or `Ambiguous(pairs.size)` by exact list size. Do not add weights, sorting, first-result selection, or method-name assumptions.

- [ ] **Step 5: Run Token tests and all JVM tests**

Run:

```powershell
& 'C:\Users\ZRhans\Documents\Codex\2026-06-18\files-mentioned-by-the-user-com\work\gradle\gradle-8.7\bin\gradle.bat' :app:testDebugUnitTest --tests '*TokenSemanticResolverTest'
& 'C:\Users\ZRhans\Documents\Codex\2026-06-18\files-mentioned-by-the-user-com\work\gradle\gradle-8.7\bin\gradle.bat' :app:testDebugUnitTest
```

Expected: both commands end with `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/io/github/colorosfeiniu/bridge/resolver/TokenSemanticResolver.kt app/src/test/java/io/github/colorosfeiniu/bridge/resolver/FakeDexSemanticIndex.kt app/src/test/java/io/github/colorosfeiniu/bridge/resolver/TokenSemanticResolverTest.kt
git commit -m "feat: resolve token hooks by semantic call chain"
```

---

### Task 6: Unique Gallery semantic resolver

**Files:**
- Create: `app/src/main/java/io/github/colorosfeiniu/bridge/resolver/GallerySemanticResolver.kt`
- Create: `app/src/test/java/io/github/colorosfeiniu/bridge/resolver/GallerySemanticResolverTest.kt`
- Modify: `app/src/test/java/io/github/colorosfeiniu/bridge/resolver/FakeDexSemanticIndex.kt`

**Interfaces:**
- Consumes: `DexSemanticIndex`, `SemanticResult`, and descriptor models.
- Produces: `GallerySemanticResolver.resolve(index: DexSemanticIndex): SemanticResult<GalleryHookRefs>`.

- [ ] **Step 1: Write a complete renamed graph success test**

Create arbitrary Provider `random.P4`, Connection `random.C8`, Stat DTO `random.S2`, and Cache `random.K6` records:

```kotlin
val stat = method(
    "Lrandom/P4;->x(Lrandom/C8;Ljava/lang/String;)Lrandom/S2;",
    "random.P4", "x", "random.S2",
    listOf("random.C8", "java.lang.String"), true,
    strings = setOf("getGalleryStat failed for device:"),
)
val albums = method(
    "Lrandom/P4;->a(IILjava/lang/String;)Ljava/util/List;",
    "random.P4", "a", "java.util.List",
    listOf("int", "int", "java.lang.String"), false,
)
val connection = method(
    "Lrandom/P4;->c(Ljava/lang/String;Z)Lrandom/C8;",
    "random.P4", "c", "random.C8",
    listOf("java.lang.String", "boolean"), false,
)
val realAlbums = method(
    "Lrandom/P4;->r(Lrandom/C8;Ljava/lang/String;II)Ljava/util/List;",
    "random.P4", "r", "java.util.List",
    listOf("random.C8", "java.lang.String", "int", "int"), false,
)
val cache = method(
    "Lrandom/K6;->g(Ljava/lang/String;)Lrandom/S2;",
    "random.K6", "g", "random.S2",
    listOf("java.lang.String"), true,
)
val dtoToString = method(
    "Lrandom/S2;->toString()Ljava/lang/String;",
    "random.S2", "toString", "java.lang.String", emptyList(), false,
    strings = setOf("NasGalleryStatDto(photoCount=", "videoCount="),
)
```

Add exactly two non-static `int` fields to `random.S2`, resolve, and assert every returned descriptor and `ResolutionSource.SEMANTIC`.

- [ ] **Step 2: Add graph-break and ambiguity tests**

Test these independent failures:

- stat anchor absent;
- stat is not static or second parameter is not `String`;
- no instance `(int,int,String)->List` albums method on the provider;
- connection returns void;
- real-albums first parameter differs from the connection return type;
- cache is not static or returns a different DTO;
- DTO `toString` lacks either stable string;
- DTO has one or three instance `int` fields;
- two complete Provider/Cache/DTO graphs exist.

Each incomplete graph must return `SemanticResult.Missing`; two complete graphs must return `SemanticResult.Ambiguous(2)`.

- [ ] **Step 3: Run focused tests and verify the resolver is missing**

Run:

```powershell
& 'C:\Users\ZRhans\Documents\Codex\2026-06-18\files-mentioned-by-the-user-com\work\gradle\gradle-8.7\bin\gradle.bat' :app:testDebugUnitTest --tests '*GallerySemanticResolverTest'
```

Expected: compilation fails because `GallerySemanticResolver` does not exist.

- [ ] **Step 4: Implement the full closed-graph matcher**

For each stat anchor candidate, inspect only its provider class and linked types:

```kotlin
val statCandidates = index.methodsUsingStrings(
    setOf("getGalleryStat failed for device:"),
).filter {
    it.isStatic &&
        it.parameterTypeNames.size == 2 &&
        it.parameterTypeNames[1] == "java.lang.String"
}
val complete = statCandidates.flatMap { completeGroupsForStat(index, it) }
```

Implement the helper with exact shapes and no score:

```kotlin
private fun completeGroupsForStat(
    index: DexSemanticIndex,
    stat: DexMethodRecord,
): List<GalleryHookRefs> {
    val methods = index.methodsOfClass(stat.className)
    val albums = methods.filter {
        !it.isStatic &&
            it.returnTypeName == "java.util.List" &&
            it.parameterTypeNames == listOf("int", "int", "java.lang.String")
    }
    val connections = methods.filter {
        !it.isStatic &&
            it.returnTypeName != "void" &&
            it.parameterTypeNames == listOf("java.lang.String", "boolean") &&
            stat.parameterTypeNames[0] == it.returnTypeName
    }
    val caches = index.methodsByPrototype(
        returnTypeName = stat.returnTypeName,
        parameterTypeNames = listOf("java.lang.String"),
        isStatic = true,
    )
    val dtoToStrings = index.methodsOfClass(stat.returnTypeName).filter {
        !it.isStatic &&
            it.name == "toString" &&
            it.returnTypeName == "java.lang.String" &&
            it.parameterTypeNames.isEmpty() &&
            setOf("NasGalleryStatDto(photoCount=", "videoCount=")
                .all(it.usingStrings::contains)
    }
    val countFieldsByDescriptor = index.fieldsOfClass(stat.returnTypeName)
        .filter { !it.isStatic && it.typeName == "int" }
        .associateBy(DexFieldRecord::descriptor)
    if (dtoToStrings.size != 1 || countFieldsByDescriptor.size != 2) return emptyList()
    val orderedCounts = dtoToStrings.single().usingFieldDescriptors
        .distinct()
        .mapNotNull(countFieldsByDescriptor::get)
    if (orderedCounts.size != 2) return emptyList()

    return connections.flatMap { connection ->
        val realAlbums = methods.filter {
            !it.isStatic &&
                it.returnTypeName == "java.util.List" &&
                it.parameterTypeNames == listOf(
                    connection.returnTypeName,
                    "java.lang.String",
                    "int",
                    "int",
                )
        }
        albums.flatMap { album ->
            realAlbums.flatMap { real ->
                caches.map { cache ->
                    GalleryHookRefs(
                        stat = stat.toMethodRef(),
                        albums = album.toMethodRef(),
                        connection = connection.toMethodRef(),
                        realAlbums = real.toMethodRef(),
                        cache = cache.toMethodRef(),
                        photoCount = orderedCounts[0].toFieldRef(),
                        videoCount = orderedCounts[1].toFieldRef(),
                        source = ResolutionSource.SEMANTIC,
                    )
                }
            }
        }
    }
}
```

Define `DexMethodRecord.toMethodRef()` and `DexFieldRecord.toFieldRef()` beside the record types as direct property copies. Deduplicate complete groups by the seven member descriptors, then return by exact count.

Field order is deterministic from the DTO `toString` field-use order exposed by DexKit. Reject the graph unless the filtered list has exactly two distinct descriptors; do not guess by obfuscated field names.

- [ ] **Step 5: Run Gallery tests and all JVM tests**

Run:

```powershell
& 'C:\Users\ZRhans\Documents\Codex\2026-06-18\files-mentioned-by-the-user-com\work\gradle\gradle-8.7\bin\gradle.bat' :app:testDebugUnitTest --tests '*GallerySemanticResolverTest'
& 'C:\Users\ZRhans\Documents\Codex\2026-06-18\files-mentioned-by-the-user-com\work\gradle\gradle-8.7\bin\gradle.bat' :app:testDebugUnitTest
```

Expected: both commands end with `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/io/github/colorosfeiniu/bridge/resolver/DexSemanticIndex.kt app/src/main/java/io/github/colorosfeiniu/bridge/resolver/DexKitSemanticIndex.kt app/src/main/java/io/github/colorosfeiniu/bridge/resolver/GallerySemanticResolver.kt app/src/test/java/io/github/colorosfeiniu/bridge/resolver/FakeDexSemanticIndex.kt app/src/test/java/io/github/colorosfeiniu/bridge/resolver/GallerySemanticResolverTest.kt
git commit -m "feat: resolve gallery fallback as a semantic graph"
```

---

### Task 7: Coordinator, bootstrap, and existing-hook integration

**Files:**
- Create: `app/src/main/java/io/github/colorosfeiniu/bridge/resolver/ConnectionResolutionCoordinator.kt`
- Create: `app/src/main/java/io/github/colorosfeiniu/bridge/resolver/ConnectionResolutionBootstrap.kt`
- Create: `app/src/main/java/io/github/colorosfeiniu/bridge/resolver/ConnectionHookInstaller.kt`
- Create: `app/src/test/java/io/github/colorosfeiniu/bridge/resolver/ConnectionResolutionCoordinatorTest.kt`
- Modify: `app/src/main/java/io/github/colorosfeiniu/bridge/FeiniuBridgeHook.kt`
- Modify: `app/src/main/java/io/github/colorosfeiniu/bridge/GalleryStatFallback.kt`

**Interfaces:**
- Consumes: known resolver, cache, Token/Gallery semantic resolvers, descriptor validator, `ResolverLog`, and the existing hook callbacks.
- Produces:
  - `interface SemanticResolverFactory { fun open(classLoader: ClassLoader): DexSemanticIndex }`
  - `ConnectionResolutionCoordinator.resolve(classLoader, fingerprint, initialKnown): ResolvedConnectionHooks?`
  - `ConnectionResolutionBootstrap.install(lpparam: XC_LoadPackage.LoadPackageParam)`
  - `ConnectionHookInstaller.install(refs, classLoader, lpparam): InstallSummary`
  - `GalleryStatFallback.install(validated: ValidatedGalleryHooks)`

- [ ] **Step 1: Write coordinator source-order tests**

Use recording fakes to assert exact call counts:

```kotlin
@Test fun `complete known groups skip cache and DexKit`() {
    val known = KnownGroups(tokenRefs, galleryRefs)
    val result = coordinator().resolve(loader, fingerprint, initialKnown = known)
    assertEquals(ResolvedConnectionHooks(tokenRefs, galleryRefs), result)
    assertEquals(0, cache.readCount)
    assertEquals(0, semanticFactory.openCount)
}

@Test fun `known token scans only missing gallery group`() {
    val result = coordinator()
        .resolve(loader, fingerprint, initialKnown = KnownGroups(tokenRefs, null))
    assertNotNull(result?.token)
    assertNotNull(result?.gallery)
    assertEquals(0, tokenSemantic.resolveCount)
    assertEquals(1, gallerySemantic.resolveCount)
    assertEquals(1, semanticFactory.openCount)
}

@Test fun `invalid token cache rescans token without disturbing cached gallery`() {
    cache.tokenValidatorResult = false
    cache.galleryValue = galleryRefs.copy(source = ResolutionSource.CACHE)
    val result = coordinator()
        .resolve(loader, fingerprint, initialKnown = KnownGroups(null, null))
    assertEquals(1, tokenSemantic.resolveCount)
    assertEquals(0, gallerySemantic.resolveCount)
    assertEquals(ResolutionSource.CACHE, result?.gallery?.source)
}
```

Also test native-load failure, semantic query exception, ambiguous Token with successful Gallery, and a total miss returning `null`.

- [ ] **Step 2: Run focused tests and verify the coordinator is missing**

Run:

```powershell
& 'C:\Users\ZRhans\Documents\Codex\2026-06-18\files-mentioned-by-the-user-com\work\gradle\gradle-8.7\bin\gradle.bat' :app:testDebugUnitTest --tests '*ConnectionResolutionCoordinatorTest'
```

Expected: compilation fails because `ConnectionResolutionCoordinator` does not exist.

- [ ] **Step 3: Implement group-independent source ordering**

The core flow is exact and must not open the semantic factory until at least one group remains missing:

```kotlin
fun resolve(
    classLoader: ClassLoader,
    fingerprint: GalleryFingerprint,
    initialKnown: KnownGroups,
): ResolvedConnectionHooks? {
    var token = initialKnown.token
    var gallery = initialKnown.gallery
    if (token == null) token = cache.readToken(fingerprint) {
        validator.validateToken(it, classLoader) != null
    }
    if (gallery == null) gallery = cache.readGallery(fingerprint) {
        validator.validateGallery(it, classLoader) != null
    }
    if (token == null || gallery == null) {
        runCatching {
            semanticFactory.open(classLoader).use { index ->
                if (token == null) {
                    token = (tokenResolver.resolve(index) as? SemanticResult.Found)?.value
                    token?.let { cache.writeToken(fingerprint, it) }
                }
                if (gallery == null) {
                    gallery = (galleryResolver.resolve(index) as? SemanticResult.Found)?.value
                    gallery?.let { cache.writeGallery(fingerprint, it) }
                }
            }
        }.onFailure { error ->
            resolverLog.event(
                "semantic-scan",
                mapOf("result" to "unavailable", "type" to error.javaClass.simpleName),
            )
        }
    }
    return if (token == null && gallery == null) null else ResolvedConnectionHooks(token, gallery)
}
```

Log each group with source, candidate count, result, and elapsed milliseconds. Cache failure results only in coordinator instance fields for the current process; never persist failure records.

- [ ] **Step 4: Refactor hook installation behind one final validator**

`ConnectionHookInstaller.install` must:

1. call `validateToken` and `validateGallery` again;
2. invalidate only a cache-sourced group that fails;
3. install Token prefix fallback and decrypt diagnostics only after both Token methods validate;
4. call `GalleryStatFallback.install(validatedGallery)` only after all seven Gallery members validate;
5. return `InstallSummary(tokenInstalled: Boolean, galleryInstalled: Boolean)`.

Change `GalleryStatFallback` from:

```kotlin
fun install(classLoader: ClassLoader)
```

to:

```kotlin
fun install(validated: ValidatedGalleryHooks) {
    val installed = InstalledVariant(
        cacheMethod = validated.cache,
        connectionMethod = validated.connection,
        realAlbumsMethod = validated.realAlbums,
        photoCount = { value -> validated.photoCount.getInt(value) },
        videoCount = { value -> validated.videoCount.getInt(value) },
    )
    XposedBridge.hookMethod(validated.stat, StatHook(installed))
    XposedBridge.hookMethod(validated.albums, AlbumsHook(installed))
    log("gallery stat fallback installed source=validated")
}
```

Delete only `GalleryVariant`, `VARIANTS`, and hard-coded member-name lookup from `GalleryStatFallback`; preserve its cache/real-albums behavior, bounded logs, eligible-error rules, pagination argument order, and `InstalledVariant`.

- [ ] **Step 5: Add one-shot `Application.attach` bootstrap**

At `handleLoadPackage`, call `KnownConnectionResolver.resolve` immediately. If both groups are complete, combine and install synchronously and do not hook `Application.attach`. If either group is missing, retain that `KnownGroups` value and hook:

```kotlin
XposedHelpers.findAndHookMethod(
    Application::class.java,
    "attach",
    Context::class.java,
    object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val context = param.args[0] as Context
            runOnce(lpparam, context, initialKnown)
        }
    },
)
```

`runOnce` uses a synchronized state enum `NEW/RUNNING/DONE`, waits for an in-progress result, and never starts a second scan. Build `GalleryFingerprint` from `PackageManager.getPackageInfo`, `applicationInfo.sourceDir`, and sorted `splitSourceDirs.orEmpty()`. Use SharedPreferences file `coloros_feiniu_bridge` through `context.createDeviceProtectedStorageContext()` when available.

Catch and log `UnsatisfiedLinkError` as:

```text
resolver stage=native-load result=unavailable type=UnsatisfiedLinkError
```

- [ ] **Step 6: Update `FeiniuBridgeHook` without moving unrelated hooks**

Replace only these calls:

```kotlin
installPrefixFallback(lpparam)
GalleryStatFallback.install(lpparam.classLoader)
```

with:

```kotlin
ConnectionResolutionBootstrap.install(lpparam)
```

Move `PrefixFallbackHook` and `TokenDecryptionDiagnosticHook` visibility from `private` to `internal` or expose exact installer functions so `ConnectionHookInstaller` reuses their current behavior. Keep `installPrivateLanTlsCompatibility`, backup, notification, temperature, pause, mobile-data, and preference hooks in the same order.

- [ ] **Step 7: Run unit tests and production compilation**

Run:

```powershell
& 'C:\Users\ZRhans\Documents\Codex\2026-06-18\files-mentioned-by-the-user-com\work\gradle\gradle-8.7\bin\gradle.bat' :app:testDebugUnitTest :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`. Inspect the test report and ensure coordinator tests prove the known path never opens DexKit.

- [ ] **Step 8: Commit**

```powershell
git add app/src/main/java/io/github/colorosfeiniu/bridge/FeiniuBridgeHook.kt app/src/main/java/io/github/colorosfeiniu/bridge/GalleryStatFallback.kt app/src/main/java/io/github/colorosfeiniu/bridge/resolver/ConnectionResolutionCoordinator.kt app/src/main/java/io/github/colorosfeiniu/bridge/resolver/ConnectionResolutionBootstrap.kt app/src/main/java/io/github/colorosfeiniu/bridge/resolver/ConnectionHookInstaller.kt app/src/test/java/io/github/colorosfeiniu/bridge/resolver/ConnectionResolutionCoordinatorTest.kt
git commit -m "feat: bootstrap semantic connection hook resolution"
```

---

### Task 8: Synthetic DEX instrumentation tests

**Files:**
- Create: `app/src/androidTest/java/io/github/colorosfeiniu/bridge/resolver/DexKitSemanticResolverInstrumentedTest.kt`
- Create: `app/src/androidTest/java/io/github/colorosfeiniu/bridge/resolver/OfflineGalleryResolverInstrumentedTest.kt`
- Create: `app/src/androidTest/java/io/github/colorosfeiniu/bridge/resolver/fixture/unique/RenamedToken.kt`
- Create: `app/src/androidTest/java/io/github/colorosfeiniu/bridge/resolver/fixture/unique/RenamedGallery.kt`
- Create: `app/src/androidTest/java/io/github/colorosfeiniu/bridge/resolver/fixture/ambiguous/SecondRenamedToken.kt`
- Create: `app/src/androidTest/java/io/github/colorosfeiniu/bridge/resolver/fixture/broken/BrokenGallery.kt`

**Interfaces:**
- Consumes: production `DexKitSemanticIndex`, `TokenSemanticResolver`, and `GallerySemanticResolver`.
- Produces: on-device proof that compiled DEX renaming is tolerated and ambiguous/broken graphs fail closed.

- [ ] **Step 1: Add compiled fixture classes with stable strings**

The unique Token fixture must force all anchors and an actual prefix call into DEX:

```kotlin
class RenamedToken {
    fun z(): String {
        val className = "com.oplus.hardware.cryptoeng.CryptoEngManager"
        return className.substringBeforeLast('.')
    }

    fun q(first: String, second: String): String {
        val tag = "TokenDecryptor"
        val cipher = "AES/GCM/NoPadding"
        val digest = "SHA-256"
        return listOf(tag, cipher, digest, z(), first, second).joinToString("|")
    }
}
```

The unique Gallery fixture must compile the exact Provider/Connection/Cache/DTO signatures, throw an exception containing `getGalleryStat failed for device:`, and have DTO `toString` reference `NasGalleryStatDto(photoCount=` then `videoCount=` and the two int fields in that order. The broken fixture differs only in real-albums parameter 1 (`String` instead of its connection type). The ambiguous fixture is a second complete Token pair with different names.

- [ ] **Step 2: Write instrumentation assertions**

Inject `searchPackages` into `DexKitSemanticIndex.create` so each test scans only its fixture package:

```kotlin
@RunWith(AndroidJUnit4::class)
class DexKitSemanticResolverInstrumentedTest {
    @Test fun uniqueRenamedFixturesResolve() {
        DexKitSemanticIndex.create(
            javaClass.classLoader!!,
            searchPackages = setOf("io.github.colorosfeiniu.bridge.resolver.fixture.unique"),
        ).use { index ->
            assertTrue(TokenSemanticResolver().resolve(index) is SemanticResult.Found)
            assertTrue(GallerySemanticResolver().resolve(index) is SemanticResult.Found)
        }
    }

    @Test fun twoCompleteTokenPairsAreRejected() {
        DexKitSemanticIndex.create(
            javaClass.classLoader!!,
            searchPackages = setOf(
                "io.github.colorosfeiniu.bridge.resolver.fixture.unique",
                "io.github.colorosfeiniu.bridge.resolver.fixture.ambiguous",
            ),
        ).use { index ->
            assertEquals(
                SemanticResult.Ambiguous(candidateCount = 2),
                TokenSemanticResolver().resolve(index),
            )
        }
    }

    @Test fun brokenGalleryTypeGraphIsRejected() {
        DexKitSemanticIndex.create(
            javaClass.classLoader!!,
            searchPackages = setOf("io.github.colorosfeiniu.bridge.resolver.fixture.broken"),
        ).use { index ->
            assertTrue(GallerySemanticResolver().resolve(index) is SemanticResult.Missing)
        }
    }
}
```

Add an APK-path overload used only by offline verification:

```kotlin
fun create(
    apkPath: String,
    searchPackages: Set<String>,
): DexKitSemanticIndex {
    System.loadLibrary("dexkit")
    return DexKitSemanticIndex(
        bridge = DexKitBridge.create(apkPath),
        searchPackages = searchPackages,
    )
}
```

Define the filter annotation in the same file, then make `OfflineGalleryResolverInstrumentedTest` read a required instrumentation argument, scan only `com.oplus.aiunit.vision`, and emit descriptor-only evidence:

```kotlin
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class OfflineOnly

@OfflineOnly
@RunWith(AndroidJUnit4::class)
class OfflineGalleryResolverInstrumentedTest {
    @Test fun resolveGalleryApk() {
        val apkPath = InstrumentationRegistry.getArguments().getString("galleryApkPath")
        assertNotNull("galleryApkPath instrumentation argument", apkPath)
        val started = SystemClock.elapsedRealtime()
        DexKitSemanticIndex.create(
            apkPath = apkPath!!,
            searchPackages = setOf("com.oplus.aiunit.vision"),
        ).use { index ->
            val token = TokenSemanticResolver().resolve(index)
            val gallery = GallerySemanticResolver().resolve(index)
            println(formatOfflineResult(token, gallery, SystemClock.elapsedRealtime() - started))
            assertTrue(token is SemanticResult.Found || gallery is SemanticResult.Found)
        }
    }
}
```

`formatOfflineResult` returns one line beginning `OFFLINE_RESOLVER`; it includes only `tokenResult`, `tokenCandidates`, `tokenPrefixDescriptor`, `tokenDecryptDescriptor`, `galleryResult`, `galleryCandidates`, the seven Gallery member descriptors, and `elapsedMs`. Missing descriptors are written as `-`.

- [ ] **Step 3: Build the instrumentation APK and verify native libraries are packaged**

Run:

```powershell
& 'C:\Users\ZRhans\Documents\Codex\2026-06-18\files-mentioned-by-the-user-com\work\gradle\gradle-8.7\bin\gradle.bat' :app:assembleDebugAndroidTest
& 'C:\Users\ZRhans\AppData\Local\Android\Sdk\build-tools\35.0.0\aapt2.exe' dump files app\build\outputs\apk\debug\app-debug.apk | Select-String 'lib/.*/libdexkit.so'
```

Expected: build succeeds and the APK lists `libdexkit.so` under each DexKit-supported packaged ABI.

- [ ] **Step 4: Run instrumentation on an attached test device**

First:

```powershell
& 'C:\Users\ZRhans\AppData\Local\Android\Sdk\platform-tools\adb.exe' devices
```

If one authorized device is listed, run:

```powershell
& 'C:\Users\ZRhans\Documents\Codex\2026-06-18\files-mentioned-by-the-user-com\work\gradle\gradle-8.7\bin\gradle.bat' :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notAnnotation=io.github.colorosfeiniu.bridge.resolver.OfflineOnly
```

Annotate `OfflineGalleryResolverInstrumentedTest` with a local `@OfflineOnly` annotation so the normal connected run excludes it. Expected: all three synthetic tests pass. If no authorized device is listed, record `NOT RUN: no authorized Android device` in the task handoff; do not claim the synthetic DEX runtime test passed.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/io/github/colorosfeiniu/bridge/resolver/DexKitSemanticIndex.kt app/src/androidTest/java/io/github/colorosfeiniu/bridge/resolver
git commit -m "test: verify semantic resolver against compiled dex"
```

---

### Task 9: Offline Gallery verification, release checks, and documentation

**Files:**
- Create: `scripts/verify-semantic-resolver.ps1`
- Modify: `app/build.gradle.kts`
- Modify: `README.md`
- Modify: `CHANGELOG.md`
- Use without committing: `.analysis/gallery-apks/`
- Generate without committing: `.analysis/resolver-reports/`

**Interfaces:**
- Consumes: completed resolver, ignored Gallery APK inputs, Android build tools.
- Produces: reproducible verification output, final APK, v2 signature evidence, hash, and device acceptance checklist.

- [ ] **Step 1: Write the verification script before changing docs**

The script accepts:

```powershell
param(
    [string]$Gradle = 'C:\Users\ZRhans\Documents\Codex\2026-06-18\files-mentioned-by-the-user-com\work\gradle\gradle-8.7\bin\gradle.bat',
    [string]$JavaHome = 'C:\Users\ZRhans\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2',
    [string]$AndroidSdk = 'C:\Users\ZRhans\AppData\Local\Android\Sdk',
    [string]$GalleryApkDirectory = '.analysis\gallery-apks'
)
```

It must:

1. set `JAVA_HOME`;
2. run `:app:testDebugUnitTest`, `:app:assembleDebugAndroidTest`, and `:app:assembleDebug`;
3. fail if `aapt2 dump files` finds no `libdexkit.so`;
4. run `apksigner verify --verbose` and require `Verified using v2 scheme (APK Signature Scheme v2): true`;
5. print final APK path, byte length, SHA-256, and signer certificate SHA-256;
6. enumerate only local APK filenames, sizes, and hashes under `GalleryApkDirectory`;
7. when one authorized `adb` device exists, install `app-debug.apk` and `app-debug-androidTest.apk`, push each present 16.40.8/16.40.13/16.40.22 APK to `/data/local/tmp/coloros-feiniu-resolver/<sha256>.apk`, and run:

```powershell
$adb = Join-Path $AndroidSdk 'platform-tools\adb.exe'
& $adb shell am instrument -w `
    -e class io.github.colorosfeiniu.bridge.resolver.OfflineGalleryResolverInstrumentedTest `
    -e galleryApkPath "/data/local/tmp/coloros-feiniu-resolver/$hash.apk" `
    io.github.colorosfeiniu.bridge.test/androidx.test.runner.AndroidJUnitRunner
```

Capture only the `OFFLINE_RESOLVER` line into `.analysis/resolver-reports/<version>.txt`; fail if instrumentation reports `FAILURES`, if neither group is found, or if the output lacks that line.
8. fail if any report line matches case-insensitive `token=|deviceId=|nas=|address=|password=|payload=|response=`.

The script must output `SKIP offline Gallery <version>: APK not present` for each absent optional vendor APK. If no authorized device exists, it must output `NOT RUN offline Gallery verification: no authorized Android device`; neither condition counts as a pass.

- [ ] **Step 2: Run the script and fix only resolver-related failures**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\verify-semantic-resolver.ps1
```

Expected:

- all JVM tests pass;
- instrumentation and debug APKs build;
- `libdexkit.so` is present;
- v2 verification is true;
- no sensitive report fields are present;
- optional Gallery APKs are either verified with role descriptors or explicitly reported as skipped.

- [ ] **Step 3: Update user-facing documentation with observed behavior**

Add a concise README section that states:

- known Gallery versions use the existing mapping without DEX scanning;
- unknown versions scan only unresolved connection groups on first process start;
- successful results are fingerprint-cached and revalidated;
- ambiguity or native-load failure disables only the unresolved group;
- DexKit increases APK size and cold-start resolution may take up to the 5-second target;
- TLS, backup-temperature/state, and mobile-data features still use exact mappings.

Set `versionCode = 14` and `versionName = "0.3.6"` in `app/build.gradle.kts`. Add CHANGELOG entries only for behavior actually verified in Step 2. Do not state that all future Gallery versions are guaranteed compatible.

- [ ] **Step 4: Run a clean final verification and inspect the diff**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\verify-semantic-resolver.ps1
git diff --check
git status --short
git diff -- app/src/main app/src/test app/src/androidTest app/build.gradle.kts scripts/verify-semantic-resolver.ps1 README.md CHANGELOG.md
```

Expected: verification succeeds, `git diff --check` prints nothing, and no vendor APK/DEX/JADX files appear in the staged or unstaged source diff.

- [ ] **Step 5: Commit documentation and verification**

```powershell
git add app/build.gradle.kts scripts/verify-semantic-resolver.ps1 README.md CHANGELOG.md
git commit -m "docs: document semantic gallery compatibility"
```

- [ ] **Step 6: Build the user-test APK and capture release evidence**

Run:

```powershell
$env:JAVA_HOME='C:\Users\ZRhans\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2'
& 'C:\Users\ZRhans\Documents\Codex\2026-06-18\files-mentioned-by-the-user-com\work\gradle\gradle-8.7\bin\gradle.bat' :app:assembleDebug
$apk = (Resolve-Path 'app\build\outputs\apk\debug\app-debug.apk').Path
Get-FileHash -Algorithm SHA256 -LiteralPath $apk
& 'C:\Users\ZRhans\AppData\Local\Android\Sdk\build-tools\35.0.0\apksigner.bat' verify --verbose --print-certs $apk
$namedApk = Join-Path (Split-Path $apk) 'ColorOS-Feiniu-Bridge-Enhanced-v0.3.6-semantic-resolver-debug.apk'
Copy-Item -LiteralPath $apk -Destination $namedApk -Force
Get-FileHash -Algorithm SHA256 -LiteralPath $namedApk
```

Expected: both SHA-256 values match, v2 is `true`, certificate details are printed, and `app-debug.apk` remains present.

- [ ] **Step 7: Perform real-device acceptance**

On a Gallery 16.40.22 device:

1. install the APK with a signer compatible with the user's currently installed module build;
2. enable it for `com.coloros.gallery3d` in LSPosed;
3. force-stop Gallery and clear only the resolver keys when testing first-scan behavior;
4. open private-cloud albums and verify real photos render;
5. confirm first run shows `resolver source=semantic` in the test build with known connection mappings disabled;
6. restart Gallery and confirm `resolver source=cache` with no semantic scan;
7. restore the normal build and confirm known versions show `resolver source=known`;
8. confirm decrypt diagnostics report non-empty result length without content.

Hook-install logs alone do not satisfy acceptance. If private-cloud photos do not render, collect bounded `ColorOSFeiniuBridge` lines plus the Gallery error and keep the task open.

## Final Completion Gate

Before declaring the implementation complete, run the `superpowers:verification-before-completion` skill and verify all of the following:

- Every JVM test passes.
- Synthetic DEX instrumentation passes on an authorized device, or is explicitly reported as not run.
- Known paths open DexKit zero times.
- Renamed unique Token and Gallery fixtures resolve.
- Duplicate complete candidates fail closed.
- Cache invalidates on every fingerprint dimension and per-group validation failure.
- Final APK contains DexKit native libraries and passes v2 signature verification.
- Final logs and reports contain no sensitive fields.
- Gallery 16.40.22 displays real private-cloud photos on device.

## References

- DexKit quick start and packaging requirements: https://luckypray.org/DexKit/en/guide/quick-start
- DexKit `DexKitBridge.create(ClassLoader, Boolean)` and lifecycle API: https://luckypray.org/DexKit-Doc/dexkit-android/org.luckypray.dexkit/-dex-kit-bridge/-companion/index.html
- AndroidX Test stable versions: https://developer.android.com/jetpack/androidx/releases/test
