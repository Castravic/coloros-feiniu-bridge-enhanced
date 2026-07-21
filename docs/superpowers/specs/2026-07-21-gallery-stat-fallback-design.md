# Gallery Stat Failure Fallback Design

## Goal

Restore Feiniu private-cloud albums on affected ColorOS Gallery versions when `getGalleryStat` repeatedly returns gRPC `INTERNAL: upstream service error`, without enumerating every cloud photo, inventing counts, bypassing authentication, or hiding unrelated failures.

## Confirmed failure path

Gallery 16.40.13 decrypts both stored Feiniu tokens successfully through `com.oplus.aiunit.vision.op80`. The first metadata page then enters `n1g.l(0, limit, deviceId)`, which calls `n1g.G(connectionManager, deviceId)` to build the virtual `ALL_PROJECT` album. `G()` calls `n1g.J()`, and `J()` fails because `IGalleryService.getGalleryStat` returns `INTERNAL: upstream service error`. The exception prevents both the virtual album and the following real `getAlbumList` request from completing.

The same statistic call is also used by the first-screen preload optimization. That outer preload path can request a full sync after the statistic failure, but the full sync reaches `n1g.G()` and fails at the same statistic call again.

## Selected behavior

The module installs a narrowly scoped compatibility hook for the Feiniu provider implementation.

1. After `n1g.J(connectionManager, deviceId)` fails with the known `getGalleryStat` upstream error, query Gallery's existing local `ALL_PROJECT` statistic through `q6q.f(deviceId)`.
2. If a cached `n9q(photoCount, videoCount)` exists and its total is positive, return that object to the original `n1g.G()` method. Gallery then performs its normal one-item `getGalleryPhotos` cover request, builds the normal virtual `ALL_PROJECT` album, and continues with the original `getAlbumList` pagination.
3. If no cached statistic exists, or its total is zero, do not create or return a misleading `n9q`. Gallery's original `G()` treats a zero total as an empty cloud and skips `getAlbumList`, so mark that device as statless for the current process and allow the original call to fail.
4. For a statless device, intercept `n1g.l(offset, limit, deviceId)` only when its failure is the same statistic error. Obtain the existing connection through `n1g.H(deviceId, false)` and call `n1g.F(connection, deviceId, limit, offset)` directly. This returns only real Feiniu albums and deliberately omits the virtual `ALL_PROJECT` album.
5. While a device remains statless, later `n1g.l()` calls use the same direct real-album pagination mapping so offsets do not retain the virtual-album `-1` shift.

The statless marker is process-local and is not persisted. A Gallery restart retries the normal statistic endpoint and local-cache path.

## Scope and compatibility

- Enable the fallback only for the Feiniu implementation classes and method shapes verified from the target APK.
- Treat only an exception whose message or causal chain identifies `getGalleryStat failed` together with the observed upstream `INTERNAL` failure as eligible.
- Do not activate for authentication failures, permission errors, missing connections, timeouts, or failures from `getGalleryPhotos` / `getAlbumList`.
- Keep the existing `erq`, `in80`, and `op80` token-prefix compatibility.
- Discover obfuscated classes from a small candidate list and verify method parameter/return shapes before installing hooks. If shapes do not match, log unavailability and leave Gallery behavior unchanged.

## Privacy and diagnostics

Diagnostic messages contain only the fallback branch, cached counts, returned album count, and exception type. They must not include tokens, device identifiers, NAS addresses, album names, photo identifiers, or filenames.

Expected messages include:

```text
gallery stat fallback installed
gallery stat fallback used source=local-cache photos=N videos=N
gallery stat fallback cache unavailable; enabling real-albums mode
gallery stat fallback real-albums result offset=N limit=N count=N
gallery stat fallback failed stage=cache|connection|albums type=ExceptionName
```

Repeated diagnostic output is bounded per process.

## Error handling

- Cache lookup failure leaves the original statistic exception intact until the containing `n1g.l()` call reaches the direct-album fallback.
- Connection lookup or `n1g.F()` failure preserves the original or replacement throwable; the module must not return an empty success result for a failed service call.
- A successful empty `getAlbumList` response is returned as an empty list because that is a real service result.
- Hook-internal reflection errors are logged without sensitive arguments and do not crash Gallery.

## Testing

Static verification scripts will fail before implementation and then assert:

- both the cached-stat and statless real-album branches are present;
- the fallback is restricted to the known statistic failure;
- diagnostics do not expose token, device ID, NAS address, album name, or photo identifiers;
- the existing 16.40.13 `op80` compatibility checks continue to pass.

The Android build must succeed with JDK 17, Android SDK 35, and Gradle 8.7. The produced test APK must pass APK signature verification and contain the expected fallback markers. Device validation succeeds only when LSPosed logs show one of the fallback branches and Gallery displays real cloud albums/photos; hook installation alone is not success.

## Known limitations

- With a cached statistic, the virtual album count reflects the last successful metadata sync until the upstream statistic endpoint recovers.
- Without cached statistics, the virtual `ALL_PROJECT` album is absent, so unalbumed cloud photos may remain unavailable; real albums and their photos can still load.
- This design does not repair the Feiniu upstream statistic service and does not claim to be a server-side fix.
