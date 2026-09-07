[CmdletBinding()]
param(
    [Alias('Profile')][ValidateSet('formal', 'fixture', 'diagnostic')][string]$ValidationProfile,
    [ValidateSet('terrain', 'G', 'H', 'F', 'L', 'W', 'M', 'D06', 'causality', 'generation-cost', 'probe')][string]$Case,
    [ValidatePattern('^(R[1-3]|W0[1-9])$')][string]$Run,
    [ValidatePattern('^PRM[QD]?[0-9]{2,}$')][string]$Batch,
    [ValidatePattern('^(on|off|[1-9][0-9]*|chunk-write-idle|chunk-write-pending|api-pending)$')][string]$Variant,
    [ValidateSet('normal', 'mutated', 'missed')][string]$Control,
    [ValidateSet('endpoint', 'build', 'search', 'final-validation')][string]$Family,
    [string]$ChildPayload
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$encoding = New-Object Text.UTF8Encoding($false)
function Write-NewJson([string]$Path, $Value) {
    $stream = [IO.File]::Open($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
    try {
        $bytes = $encoding.GetBytes(($Value | ConvertTo-Json -Depth 20))
        $stream.Write($bytes, 0, $bytes.Length)
    } finally { $stream.Dispose() }
}
function Quoted([string]$Value) {
    if ($Value.Contains('"') -or $Value.Contains("`r") -or $Value.Contains("`n")) { throw 'Invalid process argument' }
    return '"' + $Value + '"'
}

if ($ChildPayload) {
    $payload = Get-Content -LiteralPath $ChildPayload -Raw | ConvertFrom-Json
    $childExit = 1
    try {
        $arguments = @('runTerrainBenchmarkServer', '-PterrainTestMode=benchmark')
        foreach ($name in @('Profile', 'Case', 'Run', 'Batch', 'Variant', 'Control', 'Family', 'Observer')) {
            $value = $payload.options.$name
            if ($null -eq $value) { continue }
            if ([string]$value -notmatch '^[A-Za-z0-9-]+$') { throw 'Invalid Gradle property value' }
            $arguments += "-PterrainValidation$name=$value"
        }
        $arguments += @('--offline', '--no-daemon', '--console=plain')
        $command = 'call gradlew.bat ' + ($arguments -join ' ') + ' 2>&1'
        $process = Start-Process -FilePath $env:ComSpec -ArgumentList @('/d', '/s', '/c', (Quoted $command)) `
            -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $payload.directory 'console.log') `
            -RedirectStandardError (Join-Path $payload.directory 'cmd-stderr.log')
        $clock = [Diagnostics.Stopwatch]::StartNew()
        $shutdown = $null
        $serverProcess = $null
        $timedOut = $false
        $markerPath = Join-Path $payload.output 'shutdown-start.json'
        while (-not $process.WaitForExit(250)) {
            if ($null -eq $shutdown -and (Test-Path -LiteralPath $markerPath)) {
                $shutdown = Get-Content -LiteralPath $markerPath -Raw | ConvertFrom-Json
                if ($shutdown.observerId -ne $payload.options.Observer) { throw 'Shutdown marker belongs to another instance' }
                try { $serverProcess = [Diagnostics.Process]::GetProcessById([int]$shutdown.processId) }
                catch [ArgumentException] { $serverProcess = $null }
                if ($null -ne $serverProcess -and -not $serverProcess.HasExited) {
                    $actualStart = ([DateTimeOffset]$serverProcess.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds()
                    if ([Math]::Abs($actualStart - [long]$shutdown.processStartedAt) -gt 10) { throw 'Shutdown process identity changed' }
                }
            }
            $shutdownExpired = $null -ne $serverProcess -and -not $serverProcess.HasExited -and
                [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() - [long]$shutdown.shutdownStartedAt -ge 120000
            if (-not $timedOut -and ($shutdownExpired -or $clock.Elapsed.TotalSeconds -ge 2820)) {
                $timedOut = $true
                Write-NewJson (Join-Path $payload.directory 'observer-timeout.json') ([ordered]@{
                    observerId=$payload.options.Observer; reason=$(if ($shutdownExpired) { 'SHUTDOWN_TIMEOUT' } else { 'INSTANCE_TIMEOUT' })
                    observedAt=[DateTime]::UtcNow.ToString('o'); shutdown=$shutdown
                })
                if ($shutdownExpired) { $serverProcess.Kill($true) }
                else { $process.Kill($true) }
                if (-not $process.WaitForExit(30000)) { $process.Kill($true) }
            }
            if ($timedOut -and -not $process.HasExited) { throw 'Timed-out process did not exit after termination' }
        }
        if ($null -eq $process.ExitCode) { throw 'Gradle process exit was not captured' }
        $childExit = $process.ExitCode
        Write-NewJson (Join-Path $payload.directory 'gradle-exit.json') ([ordered]@{
            observerId=$payload.options.Observer; processId=$process.Id; exitCode=$childExit; observedAt=[DateTime]::UtcNow.ToString('o')
        })
        if ($timedOut -and $childExit -eq 0) { $childExit = 1 }
    } catch {
        Write-NewJson (Join-Path $payload.directory 'child-failure.json') ([ordered]@{message=$_.Exception.ToString()})
        $childExit = 1
    } finally {
        if ($null -ne $process -and -not $process.HasExited) {
            $process.Kill($true)
            $process.WaitForExit(10000) | Out-Null
        }
    }
    exit $childExit
}

foreach ($value in @($ValidationProfile, $Case, $Run, $Batch)) { if (-not $value) { throw 'Profile, Case, Run and Batch are required' } }
if ($Family -and ($ValidationProfile -ne 'diagnostic' -or $Case -ne 'probe')) { throw 'Family is only valid for probe diagnostics' }
$suffix = "$Batch/$Run/$Case" + $(if ($Variant) { "/$Variant" } else { '' }) + $(if ($Family) { "/$Family" } else { '' })
$output = Join-Path $projectRoot "build/reports/production-remediation/$suffix"
$working = Join-Path $projectRoot "run-production-remediation/$suffix"
if ((Test-Path -LiteralPath $output) -or (Test-Path -LiteralPath $working)) { throw 'Validation instance already exists; select a new batch' }
$observerId = [Guid]::NewGuid().ToString('N')
$directory = Join-Path $projectRoot "build/reports/production-remediation-observers/$observerId"
[IO.Directory]::CreateDirectory($directory) | Out-Null
$options = [ordered]@{Profile=$ValidationProfile; Case=$Case; Run=$Run; Batch=$Batch; Observer=$observerId}
if ($Variant) { $options.Variant = $Variant }
if ($Control) { $options.Control = $Control }
if ($Family) { $options.Family = $Family }
$payloadPath = Join-Path $directory 'payload.json'
Write-NewJson $payloadPath ([ordered]@{directory=$directory; output=$output; options=$options})
$shellPath = (Get-Process -Id $PID).Path
$child = Start-Process -FilePath $shellPath `
    -ArgumentList @('-NoLogo', '-NoProfile', '-NonInteractive', '-File', (Quoted $PSCommandPath), '-ChildPayload', (Quoted $payloadPath)) `
    -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $directory 'outer-stdout.log') `
    -RedirectStandardError (Join-Path $directory 'outer-stderr.log')
if (-not $child.WaitForExit(2880000)) {
    Write-NewJson (Join-Path $directory 'outer-timeout.json') ([ordered]@{observerId=$observerId; processId=$child.Id})
    $child.Kill($true)
    if (-not $child.WaitForExit(10000)) { throw 'Observer child did not exit after termination' }
}
if ($null -eq $child.ExitCode) { throw 'Outer process exit was not captured' }
$outerExit = $child.ExitCode
Write-NewJson (Join-Path $directory 'outer-exit.json') ([ordered]@{
    observerId=$observerId; processId=$child.Id; exitCode=$outerExit; observedAt=[DateTime]::UtcNow.ToString('o')
})
$serverExitPath = Join-Path $output 'server-exit.json'
if (-not (Test-Path -LiteralPath $serverExitPath)) { throw "Server finalizer did not publish ownership evidence; raw observation retained at $directory" }
$exit = Get-Content -LiteralPath $serverExitPath -Raw | ConvertFrom-Json
if ($exit.observerId -ne $observerId) { throw 'Server output belongs to another observer' }
$gradleExitPath = Join-Path $directory 'gradle-exit.json'
$exit.outerProcessExitCode = $outerExit
$exit.outerExitReason = 'Observed child PowerShell process exit; Gradle was independently observed by that child'
if (Test-Path -LiteralPath $gradleExitPath) { $exit.gradleProcessExitCode = (Get-Content -LiteralPath $gradleExitPath -Raw | ConvertFrom-Json).exitCode }
foreach ($name in @('console.log', 'cmd-stderr.log', 'gradle-exit.json', 'outer-exit.json', 'outer-stdout.log', 'outer-stderr.log', 'child-failure.json', 'observer-timeout.json', 'outer-timeout.json')) {
    $source = Join-Path $directory $name
    if (Test-Path -LiteralPath $source) { [IO.File]::Copy($source, (Join-Path $output $name), $false) }
}
Write-NewJson (Join-Path $output 'exit.json') $exit
$launcher = Get-Content -LiteralPath (Join-Path $output 'report-launch.json') -Raw | ConvertFrom-Json
if ($launcher.main -ne 'com.scarasol.acceleratednavigation.gametest.ProductionRemediationReport') { throw 'Unexpected report entry point' }
$report = Start-Process -FilePath $launcher.java -ArgumentList @('-cp', (Quoted $launcher.classpath), $launcher.main, 'finish', (Quoted $projectRoot), (Quoted $output)) `
    -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $directory 'report-stdout.log') -RedirectStandardError (Join-Path $directory 'report-stderr.log')
if (-not $report.WaitForExit(60000)) {
    $report.Kill($true)
    if (-not $report.WaitForExit(10000)) { throw 'Report process did not exit after termination' }
    Write-NewJson (Join-Path $directory 'report-timeout.json') ([ordered]@{processId=$report.Id})
}
if ($null -eq $report.ExitCode) { throw 'Report process exit was not captured' }
Write-NewJson (Join-Path $directory 'report-exit.json') ([ordered]@{exitCode=$report.ExitCode; processId=$report.Id})
Write-Output "Observation: $directory"
Write-Output "Result: $output"
if ($outerExit -ne 0) { exit $outerExit }
exit $report.ExitCode
