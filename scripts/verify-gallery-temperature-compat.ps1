<#
.SYNOPSIS
Verifies Gallery 16.40.13 temperature method shapes, hook candidates, and hysteresis.
#>

param(
    [string]$CurrentSources,
    [string]$HookFile
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$javaIdentifier = '[\p{L}_$][\p{L}\p{N}_$]*'

if ([string]::IsNullOrWhiteSpace($CurrentSources)) {
    $CurrentSources = Join-Path $repo 'scripts\fixtures\gallery-16.40.13'
}
if ([string]::IsNullOrWhiteSpace($HookFile)) {
    $HookFile = Join-Path $repo 'app\src\main\java\io\github\colorosfeiniu\bridge\FeiniuBridgeHook.kt'
}

function Assert-Condition {
    param(
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Message
    )
    if (-not $Condition) {
        throw $Message
    }
}

function Get-FileContent {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Missing file: $Path"
    }
    return Get-Content -Raw -LiteralPath $Path
}

function Get-JavaMethodBody {
    param(
        [Parameter(Mandatory = $true)][string]$Content,
        [Parameter(Mandatory = $true)][string]$SignaturePattern,
        [Parameter(Mandatory = $true)][string]$Message
    )
    $match = [regex]::Match($Content, $SignaturePattern)
    Assert-Condition $match.Success $Message

    $openBrace = $Content.IndexOf('{', $match.Index + $match.Length)
    Assert-Condition ($openBrace -ge 0) $Message
    $depth = 0
    for ($index = $openBrace; $index -lt $Content.Length; $index++) {
        switch ($Content[$index]) {
            '{' { $depth++ }
            '}' {
                $depth--
                if ($depth -eq 0) {
                    return $Content.Substring($openBrace, $index - $openBrace + 1)
                }
            }
        }
    }
    throw $Message
}

function Get-KotlinArrayBody {
    param(
        [Parameter(Mandatory = $true)][string]$Content,
        [Parameter(Mandatory = $true)][string]$ArrayName,
        [Parameter(Mandatory = $true)][string]$Message
    )
    $pattern = "(?s)private\s+val\s+$([regex]::Escape($ArrayName))\s*=\s*arrayOf\((?<body>.*?)\)"
    $match = [regex]::Match($Content, $pattern)
    Assert-Condition $match.Success $Message
    return $match.Groups['body'].Value
}

$vision = Join-Path $CurrentSources 'com\oplus\aiunit\vision'
$conditionChecker = Join-Path $vision 'u0q.java'
$temperatureUtil = Join-Path $vision 'r570.java'
$conditionCheckerContent = Get-FileContent $conditionChecker
$temperatureUtilContent = Get-FileContent $temperatureUtil
$hookContent = Get-FileContent $HookFile

$aSignature = "public\s+final\s+PauseReason\s+a\s*\(\s*boolean\s+$javaIdentifier\s*,\s*boolean\s+$javaIdentifier\s*\)"
$bSignature = "public\s+final\s+PauseReason\s+b\s*\(\s*boolean\s+$javaIdentifier\s*,\s*boolean\s+$javaIdentifier\s*\)"
$u0qABody = Get-JavaMethodBody -Content $conditionCheckerContent -SignaturePattern $aSignature -Message 'Gallery 16.40.13 u0q.a(boolean, boolean): PauseReason shape does not match'
$null = Get-JavaMethodBody -Content $conditionCheckerContent -SignaturePattern $bSignature -Message 'Gallery 16.40.13 u0q.b(boolean, boolean): PauseReason shape does not match'
Assert-Condition -Condition ([regex]::IsMatch($u0qABody, '\br570\s*\.\s*a\s*\(\s*\)')) -Message 'Gallery 16.40.13 u0q.a method body no longer reads backup temperature through r570.a()'
Assert-Condition -Condition ([regex]::IsMatch($temperatureUtilContent, 'public\s+static\s+final\s+float\s+a\s*\(\s*\)')) -Message 'Gallery 16.40.13 r570.a(): float shape does not match'

$conditionCandidates = Get-KotlinArrayBody -Content $hookContent -ArrayName 'BACKUP_CONDITION_CHECKER_CLASSES' -Message 'Hook has no BACKUP_CONDITION_CHECKER_CLASSES array'
$temperatureCandidates = Get-KotlinArrayBody -Content $hookContent -ArrayName 'TEMPERATURE_UTIL_CLASSES' -Message 'Hook has no TEMPERATURE_UTIL_CLASSES array'
Assert-Condition -Condition ($conditionCandidates -match '"com\.oplus\.aiunit\.vision\.u0q"') -Message 'Hook does not cover Gallery 16.40.13 condition checker u0q in BACKUP_CONDITION_CHECKER_CLASSES'
Assert-Condition -Condition ($temperatureCandidates -match '"com\.oplus\.aiunit\.vision\.r570"') -Message 'Hook does not cover Gallery 16.40.13 temperature utility r570 in TEMPERATURE_UTIL_CLASSES'

Assert-Condition -Condition ($hookContent -notmatch '\blastForeground\b') -Message 'Temperature hysteresis still contains lastForeground, so app state changes can incorrectly clear a high-temperature pause'
$blockedResetCount = [regex]::Matches($hookContent, '(?m)^\s*blocked\s*=\s*false').Count
Assert-Condition -Condition ($blockedResetCount -eq 1) -Message 'Temperature hysteresis must have exactly one blocked = false runtime reset'
$retryReset = [regex]::IsMatch($hookContent, '(?s)actualTemperature\s*<=\s*CLOUD_RETRY_TEMPERATURE_C\s*->\s*\{\s*blocked\s*=\s*false')
Assert-Condition -Condition $retryReset -Message 'Temperature hysteresis must reset blocked only in the actualTemperature <= CLOUD_RETRY_TEMPERATURE_C branch'

Write-Host 'Gallery 16.40.13 temperature compatibility and hysteresis verified.'
