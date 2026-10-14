param(
    [Parameter(Mandatory)][ValidateSet('formal','ablation','cold','stream','load','tick-load')]
    [string]$Stage,
    [ValidatePattern('^\.tmp/[a-z0-9-]+$')]
    [string]$EvidenceRoot = '.tmp/navigation-actual-entry-performance-20261014'
)
$ErrorActionPreference = 'Stop'
$taskWorkspace = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
Set-Location -LiteralPath $taskWorkspace
$taskCandidate = '.tmp/navigation-handoff-actual-entry-20261014/candidate-v1/actual-entry.jar'
$taskBaseline = "$EvidenceRoot/fixed4-v9/fixed4-samepath.jar"
$taskAblation = $taskBaseline
$taskProbe = '.tmp/navigation-handoff-actual-entry-20261014/probe-v3/production-probe.jar'
$taskCases = @()
if ($Stage -eq 'formal') {
    $taskOrders = @(
        @('legacy4','dynamic20','vanilla'),
        @('dynamic20','vanilla','legacy4'),
        @('vanilla','legacy4','dynamic20')
    )
    foreach ($taskMode in @('legacy4','dynamic20','vanilla')) {
        $taskCases += @{
            Name="formal-r1-$taskMode"; Mode=$taskMode;
            Distances='8,18,28'; Concurrency='128,256,512';
            Repeats=1; Cold=$false; Span=1; Load=0
        }
    }
} elseif ($Stage -eq 'ablation') {
    foreach ($taskRound in 1..3) {
        $taskCases += @{
            Name="ablation-r$taskRound-fixed4"; Mode='fixed4-samepath';
            Distances=$(if ($taskRound -eq 2) { '28,18,8' } else { '8,18,28' });
            Concurrency='512'; Repeats=1; Cold=$false; Span=1; Load=0
        }
    }
} elseif ($Stage -eq 'cold') {
    foreach ($taskMode in @('legacy4','dynamic20','vanilla')) {
        $taskCases += @{
            Name="cold-$taskMode"; Mode=$taskMode; Distances='8,18,28';
            Concurrency='512'; Repeats=1; Cold=$true; Span=1; Load=0
        }
    }
} elseif ($Stage -eq 'stream') {
    foreach ($taskMode in @('dynamic20','legacy4')) {
        $taskCases += @{
            Name="stream-$taskMode"; Mode=$taskMode; Distances='18,28';
            Concurrency='512'; Repeats=3; Cold=$false; Span=120; Load=0
        }
    }
} elseif ($Stage -eq 'load') {
    foreach ($taskLoad in @(30,40)) {
        $taskModes = if ($taskLoad -eq 30) { @('legacy4','dynamic20') } else { @('dynamic20','legacy4') }
        foreach ($taskMode in $taskModes) {
            $taskCases += @{
                Name="load$taskLoad-$taskMode"; Mode=$taskMode; Distances='18,28';
                Concurrency='128,512'; Repeats=1; Cold=$false; Span=1; Load=$taskLoad
            }
        }
    }
} else {
    & "$PSScriptRoot/run-production-checks.ps1" -Run 'current-tick-load' -Probe 'tick-load' `
        -ProductionJar $taskCandidate -ProbeJar $taskProbe -EvidenceRoot $EvidenceRoot
    exit
}
$taskManifestPath = Join-Path $taskWorkspace "$EvidenceRoot/$Stage-plan.json"
if (Test-Path -LiteralPath $taskManifestPath) { throw 'Preserve prior matrix; use fresh evidence root' }
New-Item -Path (Split-Path $taskManifestPath -Parent) -ItemType Directory -Force | Out-Null
@{
    schema='ACTUAL_ENTRY_PERFORMANCE_MATRIX_V1'; stage=$Stage;
    order=$taskCases; serialServers=$true;
    candidateSha256=(Get-FileHash -LiteralPath (Join-Path $taskWorkspace $taskCandidate)).Hash;
    baselineSha256=(Get-FileHash -LiteralPath (Join-Path $taskWorkspace $taskBaseline)).Hash;
    probeSha256=(Get-FileHash -LiteralPath (Join-Path $taskWorkspace $taskProbe)).Hash
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $taskManifestPath -Encoding utf8
foreach ($taskCase in $taskCases) {
    $taskJar = switch ($taskCase.Mode) {
        legacy4 { $taskBaseline }
        fixed4-samepath { $taskAblation }
        default { $taskCandidate }
    }
    Write-Output "MATRIX_START $($taskCase.Name)"
    & "$PSScriptRoot/run-production-budget.ps1" -Run $taskCase.Name `
        -ProductionJar $taskJar -ProbeJar $taskProbe -ComparisonMode $taskCase.Mode `
        -Vanilla:($taskCase.Mode -eq 'vanilla') -Distances $taskCase.Distances `
        -Concurrency $taskCase.Concurrency -Repeats $taskCase.Repeats `
        -SubmissionSpanTicks $taskCase.Span -AddedLoadMs $taskCase.Load `
        -ColdStart:$taskCase.Cold -EvidenceRoot $EvidenceRoot
    Write-Output "MATRIX_FINISHED $($taskCase.Name)"
}
