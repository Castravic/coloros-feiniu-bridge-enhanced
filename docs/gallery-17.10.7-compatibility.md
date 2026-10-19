# Gallery 17.10.7 compatibility

Inspection date: 2026-10-19 (Asia/Shanghai).

Input: user-supplied APK, package `com.coloros.gallery3d`, versionName `17.10.7`,
versionCode `17010007`, minSdk `34`, targetSdk `36`.

APK SHA-256: `C6E9CCE9D256BA6A504BAF67A4AEF4E11E8663B986AD6D88A6292E55AAAD53E6`.
The source file is unchanged. A local analysis copy and targeted JADX output live
under ignored `.analysis/gallery-17.10.7/`; no proprietary APK or extracted source
is included in public source.

## Verified static targets

| Purpose | Target | Resolution |
| --- | --- | --- |
| Token prefix/decryption | `rab0.e()` / `b(String, String)` | Existing structural locator, unique matching class across all DEX files |
| Temperature | `ps90.a(): float` | Existing dynamic temperature anchors |
| NAS conditions | `m1s.a/b(boolean, boolean)` | Existing `NasBackupCondChk` locator |
| Paused text | `k4s.m(int, Context)` | Existing dynamic pause-field/resource trace |
| Lifecycle | `ActivityLifecycle.c(): boolean` | Existing lifecycle mapping; returns started-activity count > 0 |
| Condition refresh | `NasBackupConditionObserver.a(boolean)` | Added fully qualified observer class to known candidates |
| Validated mobile/Wi-Fi | `NetworkMonitor.c()` / `e()` | Existing methods, semantics checked in actual bodies |
| Native TLS policy | `std.a(String, boolean)` / `sg5.a()` | Gallery's own route retained; old TLS hook has no matching target |

`NasBackupConditionObserver` is now un-obfuscated. Its instance method `a(true)`
removes a pending callback and dispatches an immediate condition refresh. Without
the added class mapping, the module's capability check correctly disabled mobile
backup because it could not install the observer hooks.

The setting fragment and COUI switch classes still exist, and the original NAS
backup/cloud-category preference keys remain in the APK. The real-APK test checks
the setting fragment's preference creation method and the switch class declaration.

## Foreground and TLS behavior

`m1s.a` uses its booleans for refresh/background-check control and reads foreground
separately through `ActivityLifecycle.c()`. As with 16.50.8 `naq`, neither boolean
is used as a foreground hint. The module uses the validated lifecycle method;
if that observation is unavailable, it conservatively uses the background limit.
The existing 45 C foreground / 43 C background / <=41 C recovery policy remains.

The new TLS route is materially different from the old `k(String): boolean`
selector. `std.a` returns Gallery's `SKIP` or `TRIM_SELF_SIGNED` policy; `sg5.a`
constructs the gRPC channel and selects its built-in socket factory for IP hosts.
The module does not hook these new methods or substitute their certificate policy.
The old `ktc0` / `j6d0` candidates have no matching static selector in this APK,
which the real-input test checks to avoid hooking an unrelated reused class name.
This inspection does not prove a TLS handshake with a particular NAS will succeed.

## Validation

Module build: `0.4.2-dev`, versionCode `17`.

- 103 JVM tests: 100 passed, 3 optional other-version input tests skipped, zero
  failures/errors. Both real 17.10.7 and 16.50.8 APK tests ran and passed.
- Production thermal-hook regression covers lifecycle priority, control flags,
  nested/exceptional scopes and pause/recovery boundaries.
- Four PowerShell source-contract checks passed, including 16.40.22 fixtures.
- Both version-specific JADX checks (`verify-gallery-16508.py` and
  `verify-gallery-17107.py`) passed against the local inspected sources.
- Debug/release builds and release vital lint passed with Gradle Wrapper 8.7.
- The debug APK signature passed verification (v2). Release output is unsigned.

Debug APK SHA-256:
`1F8F9B5654BC26B211B822C2DEAD55BC742899088AAE67992E71AE0A1BF6DB12`.

Unsigned release APK SHA-256:
`AB91DB9024B910299ED392374E38B4586C9EFCA0316AC1FC2303697994DF49B6`.

Reproduce the optional APK check:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease "-Dgallery17107.apk.path=C:/path/to/gallery-17.10.7.apk"
python scripts/verify-gallery-17107.py .analysis/gallery-17.10.7
```

The optional APK test verifies structural token discovery, dynamic enhancement
targets and retained method contracts. It also confirms a class still loads the
stat-failure anchor. It does not execute Android reflection, native DexKit scanning,
the complete gallery-stat semantic resolver, certificate handshakes or backups.

## Remaining device acceptance

No phone was connected or APK installed during this inspection. On a compatible
libxposed framework, restart Gallery with the updated module and check:

1. Existing NAS connection and cloud album browsing still work.
2. The mobile-backup switch is enabled and capability log reports `ready=true`.
3. With Wi-Fi off, enabling the switch allows a new photo to reach the NAS;
   disabling it pauses subsequent mobile backup.
4. Restart Gallery and repeat; check network transitions and other pause reasons.
5. Confirm foreground/background thermal pause and <=41 C recovery behavior.

Use runtime logs to distinguish missing hooks, unavailable networks, other pause
reasons and actual transfer success. Static validation alone is not runtime support
certification.
