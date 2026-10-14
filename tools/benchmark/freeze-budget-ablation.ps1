param(
    [string]$CandidateJar = '.tmp/navigation-handoff-actual-entry-20261014/candidate-v1/actual-entry.jar',
    [string]$ControllerClasses = 'build/classes/java/budgetCandidate',
    [ValidatePattern('^\.tmp/[a-z0-9-]+/[a-z0-9-]+$')]
    [string]$Output = '.tmp/navigation-actual-entry-performance-20261014/fixed4'
)
$ErrorActionPreference = 'Stop'
$taskWorkspace = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$taskOutput = Join-Path $taskWorkspace $Output
if (Test-Path -LiteralPath $taskOutput) { throw 'Use a fresh ablation directory' }
New-Item -Path $taskOutput -ItemType Directory -Force | Out-Null
$taskBasePath = Join-Path $taskWorkspace $CandidateJar
$taskJarPath = Join-Path $taskOutput 'fixed4-samepath.jar'
$taskAllowed = '^(com/scarasol/acceleratednavigation/scheduler/NavigationBudgetController(\$[^/]*)?\.class|META-INF/MANIFEST\.MF)$'
$taskClassRoot = Join-Path $taskWorkspace $ControllerClasses
$taskClassFiles = @(Get-ChildItem -LiteralPath (Join-Path $taskClassRoot 'com/scarasol/acceleratednavigation/scheduler') -File |
    Where-Object { $_.Name -like 'NavigationBudgetController*.class' })
if ($taskClassFiles.Count -ne 2) { throw 'Expected controller and Choice only' }
 $taskRepack = Join-Path $taskOutput 'repack'
New-Item -Path $taskRepack -ItemType Directory -Force | Out-Null
Push-Location $taskRepack
try { & 'C:\Program Files\Java\jdk-17.0.18+8\bin\jar.exe' xf (Join-Path $taskWorkspace $CandidateJar) }
finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw 'jar extraction failed' }
foreach ($taskClass in $taskClassFiles) {
    $taskDestination = Join-Path $taskRepack ('com/scarasol/acceleratednavigation/scheduler/' + $taskClass.Name)
    Copy-Item -LiteralPath $taskClass.FullName -Destination $taskDestination -Force
}
& 'C:\Program Files\Java\jdk-17.0.18+8\bin\jar.exe' cfm $taskJarPath `
    (Join-Path $taskRepack 'META-INF/MANIFEST.MF') -C $taskRepack .
if ($LASTEXITCODE -ne 0) { throw 'jar repack failed' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Get-EntryHash($entry) {
    $taskStream = $entry.Open()
    $taskSha = [Security.Cryptography.SHA256]::Create()
    try { [BitConverter]::ToString($taskSha.ComputeHash($taskStream)).Replace('-','') }
    finally { $taskStream.Dispose(); $taskSha.Dispose() }
}
$taskBase = [IO.Compression.ZipFile]::OpenRead($taskBasePath)
$taskAblation = [IO.Compression.ZipFile]::OpenRead($taskJarPath)
$taskChanged = @()
try {
    if ($taskBase.Entries.Count -ne $taskAblation.Entries.Count) { throw 'Jar entry count changed' }
    foreach ($taskEntry in $taskBase.Entries) {
        if ([string]::IsNullOrEmpty($taskEntry.Name)) { continue }
        $taskOther = $taskAblation.GetEntry($taskEntry.FullName)
        if (-not $taskOther) { throw ('Missing entry: ' + $taskEntry.FullName) }
        if ((Get-EntryHash $taskEntry) -ne (Get-EntryHash $taskOther)) {
            if ($taskEntry.FullName -notmatch $taskAllowed) { throw ('Unrelated bytecode changed: ' + $taskEntry.FullName) }
            $taskChanged += $taskEntry.FullName
        }
    }
    $taskUnexpected = @($taskChanged | Where-Object { $_ -notmatch $taskAllowed })
    if ($taskChanged.Count -lt 1 -or $taskUnexpected.Count -gt 0) {
        throw 'Only controller bytecode should change'
    }
} finally { $taskBase.Dispose(); $taskAblation.Dispose() }
$taskGeneratedController = Join-Path $taskWorkspace 'build/budget-candidate/fixed4/source/com/scarasol/acceleratednavigation/scheduler/NavigationBudgetController.java'
@{
    schema='FIXED4_SAMEPATH_BUDGET_ABLATION_V1'; researchOnly=$true;
    candidateJar=$CandidateJar; candidateSha256=(Get-FileHash -LiteralPath $taskBasePath).Hash;
    ablationSha256=(Get-FileHash -LiteralPath $taskJarPath).Hash;
    changedEntries=$taskChanged; unaffectedEntriesByteIdentical=$true;
    generatedControllerSha256=(Get-FileHash -LiteralPath $taskGeneratedController).Hash
} | ConvertTo-Json -Depth 5 |
    Set-Content -LiteralPath (Join-Path $taskOutput 'identity.json') -Encoding utf8
Get-Content -LiteralPath (Join-Path $taskOutput 'identity.json')
