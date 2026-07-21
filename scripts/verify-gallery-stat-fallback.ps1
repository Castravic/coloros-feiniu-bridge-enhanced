<#
.SYNOPSIS
Verifies the Gallery statistic fallback contract without exposing private-cloud data.
#>

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
    'cached\.photos\.toLong\(\) \+ cached\.videos\.toLong\(\) <= 0L',
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
