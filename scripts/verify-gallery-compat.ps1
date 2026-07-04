<#
.SYNOPSIS
Verifies that FeiniuBridgeHook keeps hook candidates for both tested Gallery versions.

.DESCRIPTION
By default this uses sibling jadx output directories from this workspace:
../gallery-jadx/sources and ../gallery-16.40.8-jadx/sources.
Pass -OldSources and -NewSources to run it against other decompiled Gallery sources.
#>

param(
    [string]$OldSources,
    [string]$NewSources,
    [string]$HookFile
)

$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
$workspace = Split-Path -Parent $repo

if ([string]::IsNullOrWhiteSpace($HookFile)) {
    $HookFile = Join-Path $repo 'app\src\main\java\io\github\colorosfeiniu\bridge\FeiniuBridgeHook.kt'
}

if ([string]::IsNullOrWhiteSpace($OldSources)) {
    $OldSources = Join-Path $workspace 'gallery-jadx\sources'
}

if ([string]::IsNullOrWhiteSpace($NewSources)) {
    $NewSources = Join-Path $workspace 'gallery-16.40.8-jadx\sources'
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

function Assert-Path {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Message
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        throw $Message
    }
}

Assert-Path (Join-Path $OldSources 'com\oplus\aiunit\vision\bsf.java') 'Old Gallery condition checker bsf is missing'
Assert-Path (Join-Path $OldSources 'com\oplus\aiunit\vision\erq.java') 'Old Gallery token decryptor erq is missing'
Assert-Path (Join-Path $OldSources 'com\oplus\aiunit\vision\vwp.java') 'Old Gallery temperature util vwp is missing'
Assert-Path (Join-Path $OldSources 'com\oplus\aiunit\vision\stf.java') 'Old Gallery state info stf is missing'
Assert-Path (Join-Path $OldSources 'com\oplus\aiunit\vision\otf.java') 'Old Gallery state class otf is missing'

Assert-Path (Join-Path $NewSources 'com\oplus\aiunit\vision\f0q.java') 'New Gallery condition checker f0q is missing'
Assert-Path (Join-Path $NewSources 'com\oplus\aiunit\vision\in80.java') 'New Gallery token decryptor in80 is missing'
Assert-Path (Join-Path $NewSources 'com\oplus\aiunit\vision\l370.java') 'New Gallery temperature util l370 is missing'
Assert-Path (Join-Path $NewSources 'com\oplus\aiunit\vision\o3q.java') 'New Gallery state info o3q is missing'
Assert-Path (Join-Path $NewSources 'com\oplus\aiunit\vision\k3q.java') 'New Gallery state class k3q is missing'

Assert-FileContains $HookFile 'com\.oplus\.aiunit\.vision\.bsf' 'Hook source no longer covers old Gallery condition checker bsf'
Assert-FileContains $HookFile 'com\.oplus\.aiunit\.vision\.erq' 'Hook source no longer covers old Gallery token decryptor erq'
Assert-FileContains $HookFile 'com\.oplus\.aiunit\.vision\.vwp' 'Hook source no longer covers old Gallery temperature util vwp'
Assert-FileContains $HookFile 'com\.oplus\.aiunit\.vision\.stf' 'Hook source no longer covers old Gallery state info stf'
Assert-FileContains $HookFile 'com\.oplus\.aiunit\.vision\.otf\\\$h' 'Hook source no longer covers old Gallery paused state otf$h'

Assert-FileContains $HookFile 'com\.oplus\.aiunit\.vision\.f0q' 'Hook source does not cover new Gallery condition checker f0q'
Assert-FileContains $HookFile 'com\.oplus\.aiunit\.vision\.in80' 'Hook source does not cover new Gallery token decryptor in80'
Assert-FileContains $HookFile 'com\.oplus\.aiunit\.vision\.l370' 'Hook source does not cover new Gallery temperature util l370'
Assert-FileContains $HookFile 'com\.oplus\.aiunit\.vision\.o3q' 'Hook source does not cover new Gallery state info o3q'
Assert-FileContains $HookFile 'com\.oplus\.aiunit\.vision\.k3q\\\$h' 'Hook source does not cover new Gallery paused state k3q$h'

Write-Host 'Gallery compatibility hook candidates verified.'
