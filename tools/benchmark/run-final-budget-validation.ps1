param(
    [Parameter(Mandatory)][ValidateSet('formal','checks','supplement')][string]$Stage
)
$ErrorActionPreference = 'Stop'
$taskWorkspace = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
Set-Location -LiteralPath $taskWorkspace
$taskRoot = '.tmp/navigation-dynamic-budget-production-20261014'
$taskCandidate = "$taskRoot/formal-frozen-v2/dynamic20-grace3.jar"
$taskBaseline = "$taskRoot/baseline-fead3a1/accelerated_navigation-0.1.0-all.jar"
$taskProbe = "$taskRoot/formal-frozen-v2/production-probe.jar"
if ($Stage -eq 'formal') {
    foreach ($taskRepeat in 1..5) {
        & "$PSScriptRoot/run-production-budget.ps1" -Run "final-d20-r$taskRepeat" `
            -ProductionJar $taskCandidate -ProbeJar $taskProbe
    }
    $taskRuns = @((1..5 | ForEach-Object { "formal-base-r$_" })) +
        @((1..5 | ForEach-Object { "final-d20-r$_" }))
    node "$PSScriptRoot/analyze-production-budget.mjs" $taskRoot final-five-repeat-comparison @taskRuns
    if ($LASTEXITCODE -ne 0) { throw 'Formal analysis failed' }
} elseif ($Stage -eq 'checks') {
    foreach ($taskCheck in @('speed','boundaries','wait','replay','tick-load','lifecycle')) {
        & "$PSScriptRoot/run-production-checks.ps1" -Run "final-$taskCheck" `
            -ProductionJar $taskCandidate -Probe $taskCheck `
            -ProbeJar "$taskRoot/formal-frozen-v2/diagnostic-probe.jar"
    }
} else {
    $taskCases = @(
        @{ Name='cold'; Concurrency='512,128'; ColdStart=$true; Span=1; Load=0 },
        @{ Name='continuous-hot'; Concurrency='512'; ColdStart=$false; Span=32; Load=0 },
        @{ Name='continuous-cold'; Concurrency='512'; ColdStart=$true; Span=32; Load=0 },
        @{ Name='load30'; Concurrency='128,512'; ColdStart=$false; Span=1; Load=30 },
        @{ Name='load40'; Concurrency='128,512'; ColdStart=$false; Span=1; Load=40 }
    )
    foreach ($taskCase in $taskCases) {
        $taskModes = if ($taskCase.Name -in @('continuous-hot','load30')) {
            @('candidate','baseline')
        } else { @('baseline','candidate') }
        foreach ($taskMode in $taskModes) {
            $taskJar = if ($taskMode -eq 'baseline') { $taskBaseline } else { $taskCandidate }
            & "$PSScriptRoot/run-production-budget.ps1" -Run "final-$($taskCase.Name)-$taskMode" `
                -ProductionJar $taskJar -ProbeJar "$taskRoot/formal-frozen-v2/diagnostic-probe.jar" `
                -Concurrency $taskCase.Concurrency -SubmissionSpanTicks $taskCase.Span `
                -AddedLoadMs $taskCase.Load -ColdStart:$taskCase.ColdStart
        }
        node "$PSScriptRoot/analyze-production-budget.mjs" $taskRoot "final-$($taskCase.Name)-comparison" `
            "final-$($taskCase.Name)-baseline" "final-$($taskCase.Name)-candidate"
        if ($LASTEXITCODE -ne 0) { throw ('Supplement analysis failed: ' + $taskCase.Name) }
    }
}
