# Gallery 16.50.8 compatibility investigation

Input package: `com.coloros.gallery3d`, versionName `16.50.8`, versionCode `16050008`.
SHA-256: `9A8E73836F5329B16B3DB9EE4CE842CFEDE25BA275040ABDA2A738F2EA16BFC1`.

## Confirmed static changes

| Purpose | 16.40.22 | 16.50.8 |
| --- | --- | --- |
| Backup condition / cached result | u0q.a / b(boolean, boolean) | naq.a / b(boolean, boolean) |
| Notification condition | d4q$a.d(boolean) | wdq$a.d(boolean) |
| Condition refresh | z2q.a(boolean) | zaq.a(boolean) |
| Backup state / paused state | d4q / z3q$h | wdq / sdq$h |
| Temperature | t570.a() | oh70.a() |
| Activity lifecycle | c50.b() | k11.b() |
| Token prefix / decrypt | qp80.e() / b(String, String) | y190.e() / b(String, String) |
| Private LAN TLS selector | ktc0.k(String) | j6d0.k(String) |

`NetworkMonitor.c()` still checks validated MOBILE; `e()` still checks validated WIFI.
The new `naq.a()` and `wdq$a.d()` still return `NO_WLAN` when `e()` is false.
Missing class mappings prevent the module's evaluation scope and observer refresh from
being installed, explaining why the injected switch can remain visible but ineffective.

## Integrated maintenance update (2026-10-19)

The working tree integrates the locally available main snapshot `006511c` (0.4.0,
libxposed API 102), retaining this branch's 16.50.8 mappings. Version: `0.4.1-dev`,
versionCode `16`. The earlier 0.3.5 / 0.3.7-dev artifacts are superseded by this build.
Original uncommitted sources and investigation notes are preserved locally under
`outputs/maintenance-before-integration/`; they are not included in public source.

Token discovery uses the existing known-name/structural path with semantic fallback.
Temperature, condition checker and paused text use the main branch's dynamic locator.
Fixed mappings remain for `j6d0.k`, `zaq.a`, `wdq$a.d` and `k11.b`, with reflection
signature checks. The private-LAN TLS policy remains restricted to RFC1918 IPv4.

### Foreground contract

The real `naq.a(boolean z, boolean z2)` implementation uses `z` to refresh a cached
external-app check and `z2` to skip that check. It observes app foreground separately
through `k11.b()`, which returns `d > 0` in an ActivityLifecycleCallbacks implementation.
The partial DEX dump of `naq.b` also shows the first argument bypassing its cache.
Neither boolean is a reliable app-foreground state.

The thermal policy therefore prefers the validated lifecycle method. For `naq`, no
boolean argument is used as a fallback; if lifecycle observation is unavailable, it
uses the conservative background threshold. Older parameter-contract fallback remains
available for earlier checkers. Nested scopes restore their outer hint on normal or
exceptional exit. A high-temperature pause still persists until <=41 C.

### Mobile backup availability

Readiness requires installed WLAN interception, a validated-mobile method, installed
NAS checker scopes, and constructor hooks for a uniquely named `a(boolean): void`
condition refresher. Missing dependencies disable both the UI switch and the effective
mobile-data policy without deleting a previously saved opt-in. The NAS preference's
original network summary is left intact when unavailable. A bounded startup summary
records `ready` and missing dependency names, without account, device or token data.

### Verification

- Full JVM test run with the local Gallery 16.50.8 APK: 102 tests, 99 passed,
  3 optional older Gallery / MyDevices inputs skipped, 0 failures or errors.
- The real-input test reads all APK DEX files and finds exactly `y190`, dynamically
  locates `oh70`, `naq.a/b`, `wdq.m`, and validates the retained TLS/lifecycle/refresh/
  notification method contracts directly from DEX.
- All four PowerShell source-contract checks pass, including 16.40.22 fixtures.
- `verify-gallery-16508.py` passes against actual local JADX outputs.
- Debug and unsigned release APK builds pass, including release vital lint.
- Gradle 8.7 Wrapper uses the distribution checksum published by Gradle.

Reproduce local real-input validation (the proprietary APK is not committed):

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease "-Dgallery16508.apk.path=C:/path/to/gallery-16.50.8.apk"
python scripts/verify-gallery-16508.py .analysis/gallery-16.50.8
```

The existing `gallery.apk.path` optional test specifically targets 17.9.24; the new
`gallery16508.apk.path` property keeps these version-specific expectations separate.
On CI all optional proprietary APK tests skip; fixture and behavior tests still run.
This test uses the project's pure JVM structural locator, not a device-native DexKit
scan or a running Android class loader. Device-native semantic scan/cache behavior
still requires runtime evidence.

### Device acceptance still required

No APK was installed or run on a phone in this maintenance pass. Restart Gallery with
the updated module loaded, disable Wi-Fi, enable the mobile switch and verify a new
photo arrives on the NAS. Disable the switch and verify a subsequent backup pauses.
Repeat after restarting Gallery; confirm temperature pause/recovery, network changes,
and the absence of changes to official cloud backup. Keep logs showing capability
readiness and `validated mobile network accepted for NAS backup`. Other pause reasons
may legitimately prevent upload. This is not a claim of complete runtime support.
