<#
.SYNOPSIS
Verifies that token decryption diagnostics cover both supported Gallery decryptors without logging secrets.
#>

param(
    [string]$HookFile
)

$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($HookFile)) {
    $HookFile = Join-Path $repo 'app\src\main\java\io\github\colorosfeiniu\bridge\FeiniuBridgeHook.kt'
}

if (-not (Test-Path -LiteralPath $HookFile)) {
    throw "Missing hook source: $HookFile"
}

$source = Get-Content -Raw -LiteralPath $HookFile

$requiredPatterns = @(
    'private const val TOKEN_DECRYPT_METHOD = "b"',
    'private object TokenDecryptionDiagnosticHook',
    'method\.returnType == String::class\.java',
    'arrayOf\(String::class\.java, String::class\.java\)',
    'token decrypt result=success len=',
    'token decrypt result=empty',
    'token decrypt result=error type='
)

foreach ($pattern in $requiredPatterns) {
    if ($source -notmatch $pattern) {
        throw "Missing token decryption diagnostic source pattern: $pattern"
    }
}

$forbiddenPatterns = @(
    'token decrypt.*encryptedToken',
    'token decrypt.*deviceId',
    'token decrypt.*param\.args'
)

foreach ($pattern in $forbiddenPatterns) {
    if ($source -match $pattern) {
        throw "Token decryption diagnostics may expose sensitive input: $pattern"
    }
}

Write-Host 'Token decryption diagnostics verified.'
