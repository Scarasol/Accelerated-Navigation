param(
    [string]$BaseJar = '.tmp/navigation-dynamic-budget-production-20261014/formal-frozen-v2/dynamic20-grace3.jar',
    [string]$CompiledJar = 'build/libs/accelerated_navigation-0.1.0-all.jar',
    [string]$ProbeJar = 'build/reobfProductionProbeJar/output.jar',
    [ValidatePattern('^\.tmp/[a-z0-9-]+/[a-z0-9-]+$')]
    [string]$Output = '.tmp/navigation-handoff-actual-entry-20261014/candidate-v1'
)
$ErrorActionPreference = 'Stop'
$taskWorkspace = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$taskOutput = Join-Path $taskWorkspace $Output
if (Test-Path -LiteralPath $taskOutput) { throw 'Use a fresh candidate directory' }
New-Item -Path $taskOutput -ItemType Directory -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskAllowed = '^(com/scarasol/acceleratednavigation/(navigation/(SegmentedNavigationService|AutomaticNavigationBridge)|mixin/AutomaticNavigationMixin)(\$[^/]*)?\.class|accelerated_navigation\.refmap\.json)$'
$taskBasePath = Join-Path $taskWorkspace $BaseJar
$taskBuiltPath = Join-Path $taskWorkspace $CompiledJar
$taskJarPath = Join-Path $taskOutput 'actual-entry.jar'
Copy-Item -LiteralPath $taskBasePath -Destination $taskJarPath
$taskSource = [IO.Compression.ZipFile]::OpenRead($taskBuiltPath)
$taskTarget = [IO.Compression.ZipFile]::Open($taskJarPath, [IO.Compression.ZipArchiveMode]::Update)
$taskReplaced = @()
try {
    foreach ($taskEntry in $taskSource.Entries) {
        if ($taskEntry.FullName -notmatch $taskAllowed) { continue }
        $taskOld = $taskTarget.GetEntry($taskEntry.FullName)
        if ($taskOld) { $taskOld.Delete() }
        $taskNew = $taskTarget.CreateEntry($taskEntry.FullName)
        $taskInput = $taskEntry.Open()
        $taskDestination = $taskNew.Open()
        try { $taskInput.CopyTo($taskDestination) }
        finally { $taskInput.Dispose(); $taskDestination.Dispose() }
        $taskReplaced += $taskEntry.FullName
    }
} finally { $taskSource.Dispose(); $taskTarget.Dispose() }
function Get-EntryHash($entry) {
    $taskStream = $entry.Open()
    $taskSha = [Security.Cryptography.SHA256]::Create()
    try { [BitConverter]::ToString($taskSha.ComputeHash($taskStream)).Replace('-','') }
    finally { $taskStream.Dispose(); $taskSha.Dispose() }
}
$taskBase = [IO.Compression.ZipFile]::OpenRead($taskBasePath)
$taskCandidate = [IO.Compression.ZipFile]::OpenRead($taskJarPath)
$taskChanged = @()
$taskProductionClasses = 0
try {
    if ($taskBase.Entries.Count -ne $taskCandidate.Entries.Count) { throw 'Unexpected jar entry count change' }
    foreach ($taskEntry in $taskBase.Entries) {
        $taskOther = $taskCandidate.GetEntry($taskEntry.FullName)
        if (-not $taskOther) { throw ('Missing baseline entry: ' + $taskEntry.FullName) }
        if ($taskEntry.FullName -match '^com/scarasol/acceleratednavigation/.+\.class$') { $taskProductionClasses++ }
        if ((Get-EntryHash $taskEntry) -ne (Get-EntryHash $taskOther)) {
            if ($taskEntry.FullName -notmatch $taskAllowed) { throw ('Unrelated change: ' + $taskEntry.FullName) }
            $taskChanged += $taskEntry.FullName
        }
    }
    if ($taskCandidate.Entries | Where-Object { $_.FullName -match '^an/' }) { throw 'Test class in candidate' }
} finally { $taskBase.Dispose(); $taskCandidate.Dispose() }
Copy-Item -LiteralPath (Join-Path $taskWorkspace $ProbeJar) -Destination (Join-Path $taskOutput 'production-probe.jar')
$taskSources = @(
    'src/main/java/com/scarasol/acceleratednavigation/navigation/SegmentedNavigationService.java',
    'src/main/java/com/scarasol/acceleratednavigation/navigation/AutomaticNavigationBridge.java',
    'src/main/java/com/scarasol/acceleratednavigation/mixin/AutomaticNavigationMixin.java'
)
$taskIdentity = @{
    schema='ISOLATED_ACTUAL_ENTRY_CANDIDATE_V1'; baseJar=$BaseJar; compiledJar=$CompiledJar
    baseSha256=(Get-FileHash -LiteralPath $taskBasePath).Hash
    candidateSha256=(Get-FileHash -LiteralPath $taskJarPath).Hash
    probeSha256=(Get-FileHash -LiteralPath (Join-Path $taskOutput 'production-probe.jar')).Hash
    productionClasses=$taskProductionClasses; replacedEntries=$taskReplaced; changedEntries=$taskChanged
    unaffectedEntriesByteIdentical=$true; productionTestEntries=0
    sources=@($taskSources | ForEach-Object {
        @{path=$_;sha256=(Get-FileHash -LiteralPath (Join-Path $taskWorkspace $_)).Hash}
    })
}
$taskIdentity | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath (Join-Path $taskOutput 'identity.json') -Encoding utf8
$taskIdentity | ConvertTo-Json -Depth 7
