<#
.SYNOPSIS
Verifies known Gallery mappings and the semantic connection resolver anchors.

.DESCRIPTION
By default this uses sibling jadx output directories from this workspace:
../gallery-jadx/sources and ../gallery-16.40.8-jadx/sources.
Pass -OldSources and -NewSources to run it against other decompiled Gallery sources.
Pass -CurrentDex and -Dexdump together to verify the Gallery 16.40.13
TokenDecryptor directly from its DEX.
#>

param(
    [string]$OldSources,
    [string]$NewSources,
    [string]$Gallery164022Sources,
    [string]$HookFile,
    [string]$KnownMappingsFile,
    [string]$SemanticResolverFile,
    [string]$BuildFile,
    [string]$CurrentDex,
    [string]$Dexdump,
    [switch]$SkipLegacySourceEvidence
)

$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
$workspace = Split-Path -Parent $repo

if ([string]::IsNullOrWhiteSpace($HookFile)) {
    $HookFile = Join-Path $repo 'app\src\main\java\io\github\colorosfeiniu\bridge\FeiniuBridgeHook.kt'
}

if ([string]::IsNullOrWhiteSpace($KnownMappingsFile)) {
    $KnownMappingsFile = Join-Path $repo 'app\src\main\java\io\github\colorosfeiniu\bridge\resolver\KnownConnectionResolver.kt'
}

if ([string]::IsNullOrWhiteSpace($SemanticResolverFile)) {
    $SemanticResolverFile = Join-Path $repo 'app\src\main\java\io\github\colorosfeiniu\bridge\resolver\SemanticDexResolver.kt'
}

if ([string]::IsNullOrWhiteSpace($BuildFile)) {
    $BuildFile = Join-Path $repo 'app\build.gradle.kts'
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

function Assert-CurrentTokenDecryptor {
    param(
        [Parameter(Mandatory = $true)][string]$DexPath,
        [Parameter(Mandatory = $true)][string]$DexdumpPath
    )

    Assert-Path $DexPath "Gallery 16.40.13 DEX is missing: $DexPath"
    Assert-Path $DexdumpPath "dexdump is missing: $DexdumpPath"

    $targetDescriptor = 'Lcom/oplus/aiunit/vision/op80;'
    $inTargetClass = $false
    $foundClass = $false
    $foundPrefixMethod = $false
    $foundTokenDecryptorTag = $false
    $foundCryptoEngManager = $false
    $pendingMethodName = $false

    & $DexdumpPath -d -n $DexPath | ForEach-Object {
        $line = $_
        if ($line -match "Class descriptor\s*: '([^']+)'" ) {
            $inTargetClass = $matches[1] -eq $targetDescriptor
            if ($inTargetClass) {
                $foundClass = $true
            }
            $pendingMethodName = $false
            return
        }
        if (-not $inTargetClass) {
            return
        }
        if ($line -match "name\s*: 'e'") {
            $pendingMethodName = $true
        } elseif ($pendingMethodName -and $line -match "type\s*: '\(\)Ljava/lang/String;'") {
            $foundPrefixMethod = $true
            $pendingMethodName = $false
        } elseif ($line -match 'TokenDecryptor') {
            $foundTokenDecryptorTag = $true
        } elseif ($line -match 'CryptoEngManager') {
            $foundCryptoEngManager = $true
        }
    }

    if ($LASTEXITCODE -ne 0) {
        throw "dexdump failed with exit code $LASTEXITCODE"
    }
    if (-not $foundClass) {
        throw 'Gallery 16.40.13 token decryptor op80 is missing'
    }
    if (-not $foundPrefixMethod) {
        throw 'Gallery 16.40.13 op80.e() String prefix method is missing'
    }
    if (-not $foundTokenDecryptorTag -or -not $foundCryptoEngManager) {
        throw 'Gallery 16.40.13 op80 no longer matches the TokenDecryptor/CryptoEng flow'
    }
}

if (-not $SkipLegacySourceEvidence) {
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
}

Assert-FileContains $HookFile 'com\.oplus\.aiunit\.vision\.bsf' 'Hook source no longer covers old Gallery condition checker bsf'
Assert-FileContains $KnownMappingsFile 'com\.oplus\.aiunit\.vision\.erq' 'Known mappings no longer cover old Gallery token decryptor erq'
Assert-FileContains $HookFile 'com\.oplus\.aiunit\.vision\.vwp' 'Hook source no longer covers old Gallery temperature util vwp'
Assert-FileContains $HookFile 'com\.oplus\.aiunit\.vision\.stf' 'Hook source no longer covers old Gallery state info stf'
Assert-FileContains $HookFile 'com\.oplus\.aiunit\.vision\.otf\\\$h' 'Hook source no longer covers old Gallery paused state otf$h'

Assert-FileContains $HookFile 'com\.oplus\.aiunit\.vision\.f0q' 'Hook source does not cover new Gallery condition checker f0q'
Assert-FileContains $KnownMappingsFile 'com\.oplus\.aiunit\.vision\.in80' 'Known mappings do not cover new Gallery token decryptor in80'
Assert-FileContains $HookFile 'com\.oplus\.aiunit\.vision\.l370' 'Hook source does not cover new Gallery temperature util l370'
Assert-FileContains $HookFile 'com\.oplus\.aiunit\.vision\.o3q' 'Hook source does not cover new Gallery state info o3q'
Assert-FileContains $HookFile 'com\.oplus\.aiunit\.vision\.k3q\\\$h' 'Hook source does not cover new Gallery paused state k3q$h'

Assert-FileContains $KnownMappingsFile 'com\.oplus\.aiunit\.vision\.z0g' 'Known mappings no longer cover Gallery 16.40.8 Feiniu provider z0g'
Assert-FileContains $KnownMappingsFile 'com\.oplus\.aiunit\.vision\.b6q' 'Known mappings no longer cover Gallery 16.40.8 NAS album cache b6q'
Assert-FileContains $KnownMappingsFile 'com\.oplus\.aiunit\.vision\.y8q' 'Known mappings no longer cover Gallery 16.40.8 statistic DTO y8q'
Assert-FileContains $KnownMappingsFile 'com\.oplus\.aiunit\.vision\.n1g' 'Known mappings no longer cover Gallery 16.40.13 Feiniu provider n1g'
Assert-FileContains $KnownMappingsFile 'com\.oplus\.aiunit\.vision\.q6q' 'Known mappings no longer cover Gallery 16.40.13 NAS album cache q6q'
Assert-FileContains $KnownMappingsFile 'com\.oplus\.aiunit\.vision\.n9q' 'Known mappings no longer cover Gallery 16.40.13 statistic DTO n9q'

Assert-FileContains $SemanticResolverFile 'TokenDecryptor' 'Semantic resolver lacks the token decryptor anchor'
Assert-FileContains $SemanticResolverFile 'AES/GCM/NoPadding' 'Semantic resolver lacks the AES/GCM token anchor'
Assert-FileContains $SemanticResolverFile 'SHA-256' 'Semantic resolver lacks the token hash-chain anchor'
Assert-FileContains $SemanticResolverFile 'CryptoEngManager' 'Semantic resolver lacks the hardware crypto-chain anchor'
Assert-FileContains $SemanticResolverFile 'getGalleryStat failed for device:' 'Semantic resolver lacks the Gallery stat failure anchor'
Assert-FileContains $SemanticResolverFile 'NasGalleryStatDto\(photoCount=' 'Semantic resolver lacks the Gallery stat DTO anchor'
Assert-FileContains $BuildFile 'org\.luckypray:dexkit:2\.2\.0' 'DexKit runtime dependency is missing'

if (-not [string]::IsNullOrWhiteSpace($CurrentDex) -or -not [string]::IsNullOrWhiteSpace($Dexdump)) {
    if ([string]::IsNullOrWhiteSpace($CurrentDex) -or [string]::IsNullOrWhiteSpace($Dexdump)) {
        throw 'Pass -CurrentDex and -Dexdump together'
    }
    Assert-CurrentTokenDecryptor -DexPath $CurrentDex -DexdumpPath $Dexdump
    Assert-FileContains $KnownMappingsFile 'com\.oplus\.aiunit\.vision\.op80' 'Known mappings do not cover Gallery 16.40.13 token decryptor op80'
}

if (-not [string]::IsNullOrWhiteSpace($Gallery164022Sources)) {
    $gallery164022Vision = Join-Path $Gallery164022Sources 'com\oplus\aiunit\vision'
    $ktc0 = Join-Path $gallery164022Vision 'ktc0.java'
    $kkc0 = Join-Path $gallery164022Vision 'kkc0.java'
    $qp80 = Join-Path $gallery164022Vision 'qp80.java'
    $u0q = Join-Path $gallery164022Vision 'u0q.java'
    $d4q = Join-Path $gallery164022Vision 'd4q.java'
    $z3q = Join-Path $gallery164022Vision 'z3q.java'
    $t570 = Join-Path $gallery164022Vision 't570.java'
    $z2q = Join-Path $gallery164022Vision 'z2q.java'

    Assert-FileContains $qp80 'public\s+final\s+java\.lang\.String\s+e\s*\(\s*\)' 'Gallery 16.40.22 qp80.e(): String prefix method is missing'
    Assert-FileContains $qp80 'public\s+final\s+java\.lang\.String\s+b\s*\(\s*java\.lang\.String\s+\w+\s*,\s*java\.lang\.String\s+\w+\s*\)' 'Gallery 16.40.22 qp80.b(String, String): String token decryptor is missing'
    Assert-FileContains $qp80 'TokenDecryptor' 'Gallery 16.40.22 qp80 no longer matches the TokenDecryptor flow'
    Assert-FileContains $qp80 'CryptoEngManager' 'Gallery 16.40.22 qp80 no longer uses the CryptoEng prefix flow'
    Assert-FileContains $u0q 'PauseReason\s+a\s*\(\s*boolean\s+\w+\s*,\s*boolean\s+\w+\s*\)' 'Gallery 16.40.22 u0q backup condition method is missing'
    Assert-FileContains $d4q 'java\.lang\.String\s+[a-zA-Z0-9_$]+\s*\(\s*android\.content\.Context\s+\w+\s*\)' 'Gallery 16.40.22 d4q backup state text method is missing'
    Assert-FileContains $z3q 'class\s+h\s+extends\s+com\.oplus\.aiunit\.vision\.z3q' 'Gallery 16.40.22 z3q paused state is missing'
    Assert-FileContains $t570 'static\s+final\s+float\s+a\s*\(\s*\)' 'Gallery 16.40.22 t570 temperature method is missing'
    Assert-FileContains $z2q 'final\s+class\s+z2q' 'Gallery 16.40.22 z2q backup manager is missing'

    Assert-FileContains $ktc0 'public\s+static\s+boolean\s+k\s*\(\s*(?:java\.lang\.)?String\s+\w+\s*\)' 'Gallery 16.40.22 ktc0.k(String): boolean signature is missing'
    Assert-FileContains $ktc0 'if\s*\([^)]*\|\|\s*k\s*\(\s*\w+\s*\)\s*\)' 'Gallery 16.40.22 channel builder no longer selects compatibility TLS through ktc0.k(String)'
    Assert-FileContains $ktc0 'kkc0\s*\.\s*a\s*\(\s*\)' 'Gallery 16.40.22 IP-literal TLS branch no longer calls kkc0.a()'
    Assert-FileContains $kkc0 'new\s+(?:com\.oplus\.aiunit\.vision\.)?gic0\s*\(' 'Gallery 16.40.22 kkc0.a() no longer installs gic0'

    Assert-FileContains $HookFile 'com\.oplus\.aiunit\.vision\.ktc0' 'Hook source does not target Gallery 16.40.22 ktc0'
    Assert-FileContains $HookFile 'PRIVATE_LAN_TLS_METHOD\s*=\s*"k"' 'Hook source does not guard the Gallery 16.40.22 TLS selector method name'
    Assert-FileContains $HookFile 'Modifier\.isStatic\(method\.modifiers\)' 'Hook source does not require a static Gallery TLS selector'
    Assert-FileContains $HookFile 'method\.returnType\s*==\s*Boolean::class\.javaPrimitiveType' 'Hook source does not require a primitive boolean Gallery TLS selector result'
    Assert-FileContains $HookFile 'arrayOf\(String::class\.java\)' 'Hook source does not require exactly one String Gallery TLS selector argument'
    Assert-FileContains $HookFile 'private LAN TLS compatibility installed' 'Hook source lacks the private-LAN TLS installation diagnostic'
    Assert-FileContains $HookFile 'private LAN TLS compatibility activated' 'Hook source lacks the privacy-safe private-LAN TLS activation diagnostic'

    Assert-FileContains $KnownMappingsFile 'com\.oplus\.aiunit\.vision\.qp80' 'Known mappings do not cover Gallery 16.40.22 token decryptor qp80'
    Assert-FileContains $HookFile 'com\.oplus\.aiunit\.vision\.u0q' 'Hook source does not cover Gallery 16.40.22 condition checker u0q'
    Assert-FileContains $HookFile 'com\.oplus\.aiunit\.vision\.d4q' 'Hook source does not cover Gallery 16.40.22 backup state d4q'
    Assert-FileContains $HookFile 'com\.oplus\.aiunit\.vision\.z3q\\\$h' 'Hook source does not cover Gallery 16.40.22 paused state z3q$h'
    Assert-FileContains $HookFile 'com\.oplus\.aiunit\.vision\.d4q\\\$a' 'Hook source does not cover Gallery 16.40.22 notification state d4q$a'
    Assert-FileContains $HookFile 'com\.oplus\.aiunit\.vision\.t570' 'Hook source does not cover Gallery 16.40.22 temperature utility t570'
    Assert-FileContains $HookFile 'com\.oplus\.aiunit\.vision\.z2q' 'Hook source does not cover Gallery 16.40.22 backup observer z2q'
}

Write-Host 'Gallery compatibility hook candidates verified.'
