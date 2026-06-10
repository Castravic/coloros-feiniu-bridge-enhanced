# ColorOS Feiniu Bridge

LSPosed module for restoring Feiniu NAS access in ColorOS Gallery when Gallery cannot load the local token prefix through `cryptoeng cmd 26`.

## What It Does

- Targets only `com.coloros.gallery3d`.
- Hooks `com.oplus.aiunit.vision.erq.e()`, the Gallery-side prefix loader.
- Keeps the original `cryptoeng` path first. The module only supplies a fallback when the original method returns blank or null.
- Discovers the prefix from the installed Gallery APK dex strings when possible.
- Falls back to the currently verified Feiniu token prefix if the APK scan cannot find one.
- Declares LSPosed/Xposed minimum API `101` and recommends the Gallery scope automatically.

## How It Works

ColorOS Gallery builds Feiniu NAS `ConnectionSetupData` by decrypting the stored access token and refresh token. The token decryptor derives the AES-GCM key as:

```text
SHA-256(prefix + deviceId)
```

On affected builds, Gallery tries to obtain `prefix` through `cryptoeng cmd 26`, but the native service rejects the Gallery process with a permission error. As a result, the prefix is null, token decryption returns null, and Gallery never opens the NAS connection.

This module hooks only the prefix loading method after the original method returns. If the original method returns a usable prefix, the module does nothing. If the original method returns blank or null, the module scans the installed Gallery APK dex strings for the Feiniu `GwToken` prefix and returns it to Gallery. Gallery then continues its own token decrypt and connection flow.

The module does not fabricate tokens or connection objects. It only restores the missing local prefix value needed by the existing Gallery code path.

## What It Does Not Do

- Does not modify Gallery APK files.
- Does not modify MyDevices, databases, account bindings, or NAS records.
- Does not bypass Feiniu server authentication.
- Does not print, store, upload, or expose access tokens.
- Does not decrypt tokens outside the Gallery process.

## Compatibility

LSPosed/Xposed requirement:

- Minimum declared API: `101`
- Recommended scope: `com.coloros.gallery3d`
- Scope metadata is included in both `assets/scope.list` and `META-INF/xposed/scope.list`.

Verified environment:

- Device: OnePlus PLK110
- System: ColorOS V16.1.0 / Android 16
- Gallery: `com.coloros.gallery3d` `16.35.10`
- NAS service: Feiniu NAS on `:5667`

The module should work on nearby ColorOS Gallery versions if these stay unchanged:

- Target package: `com.coloros.gallery3d`
- Token decryptor class: `com.oplus.aiunit.vision.erq`
- Prefix loader method: `e()`
- Token key derivation: `SHA-256(prefix + deviceId)`

If OPPO/OnePlus changes class names or token derivation in a later Gallery build, this module may need an update.

## Build

GitHub Actions builds APK artifacts automatically on every push, pull request, and manual workflow run. See `.github/workflows/build.yml`.

With Android Studio, open this directory and run the `app` build task.

With a local Gradle installation:

```bash
gradle :app:assembleRelease
```

If you want a repository-local wrapper before publishing, run this once from the project root on a machine with Gradle installed:

```bash
gradle wrapper --gradle-version 8.7
```

The APK will be generated at:

```text
app/build/outputs/apk/release/app-release-unsigned.apk
```

Sign it with your own key before distribution.

## Install

1. Install the signed APK.
2. Enable it in LSPosed.
3. LSPosed should recommend `相册` / `com.coloros.gallery3d` automatically. Keep only that scope enabled.
4. Force stop Gallery or reboot.
5. Open Gallery and enter the Feiniu NAS/private cloud entry.

## Expected Logs

LSPosed log should include:

```text
ColorOSFeiniuBridge: installed for com.coloros.gallery3d
ColorOSFeiniuBridge: prefix fallback supplied len=33
```

Gallery should then continue its original connection flow and connect to the NAS service.

## Troubleshooting

- `install failed: ClassNotFoundException`: Gallery obfuscation changed. Re-identify the TokenDecryptor class.
- No `prefix fallback supplied`: the original `cryptoeng` path may already work, or the Feiniu entry was not triggered.
- Still cannot connect after fallback: check Gallery logs for `AEADBadTagException`, token expiry, NAS reachability, or account binding issues.
- Token decrypts but albums are empty: this module only restores connection setup; album sync/index state is handled by Gallery and Feiniu NAS.

Useful checks:

```bash
adb shell logcat | grep -iE 'ColorOSFeiniuBridge|FeiniuNasSDK|TokenDecryptor|NasAlbum|cryptoeng'
adb shell su -c 'ss -tnp | grep 5667'
```

## Security Notes

This module is intended for users accessing their own Feiniu NAS from their own ColorOS device. It restores a local prefix loading failure in Gallery and leaves the normal encrypted token and server validation flow intact.

Do not use this module to access devices, accounts, or NAS services you do not own or have permission to use.

## License

MIT
