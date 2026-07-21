<#
.SYNOPSIS
验证相册 16.40.13 飞牛备份温控方法形状和模块候选类。
#>

param(
    [string]$CurrentSources,
    [string]$HookFile
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot

if ([string]::IsNullOrWhiteSpace($CurrentSources)) {
    $CurrentSources = Join-Path $repo '.analysis\jadx-classes2\sources'
}
if ([string]::IsNullOrWhiteSpace($HookFile)) {
    $HookFile = Join-Path $repo 'app\src\main\java\io\github\colorosfeiniu\bridge\FeiniuBridgeHook.kt'
}

function Assert-FileContains {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Pattern,
        [Parameter(Mandatory = $true)][string]$Message
    )
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Missing file: $Path"
    }
    $content = Get-Content -Raw -LiteralPath $Path
    if ($content -notmatch $Pattern) {
        throw $Message
    }
}

$vision = Join-Path $CurrentSources 'com\oplus\aiunit\vision'
$conditionChecker = Join-Path $vision 'u0q.java'
$temperatureUtil = Join-Path $vision 'r570.java'

Assert-FileContains $conditionChecker `
    'public final PauseReason a\(boolean z, boolean z2\)' `
    '相册 16.40.13 u0q.a(boolean, boolean): PauseReason 方法形状不匹配'
Assert-FileContains $conditionChecker `
    'public final PauseReason b\(boolean z, boolean z2\)' `
    '相册 16.40.13 u0q.b(boolean, boolean): PauseReason 方法形状不匹配'
Assert-FileContains $conditionChecker `
    'float fA = r570\.a\(\)' `
    '相册 16.40.13 u0q 不再通过 r570.a() 获取备份温度'
Assert-FileContains $temperatureUtil `
    'public static final float a\(\)' `
    '相册 16.40.13 r570.a(): float 方法形状不匹配'
Assert-FileContains $HookFile `
    'com\.oplus\.aiunit\.vision\.u0q' `
    'Hook 尚未覆盖相册 16.40.13 备份条件检查器 u0q'
Assert-FileContains $HookFile `
    'com\.oplus\.aiunit\.vision\.r570' `
    'Hook 尚未覆盖相册 16.40.13 温度工具 r570'

Write-Host '相册 16.40.13 温控兼容候选已验证。'
