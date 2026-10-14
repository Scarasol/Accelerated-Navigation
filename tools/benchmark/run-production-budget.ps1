param(
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]+$')][string]$Run,
    [Parameter(Mandatory)][string]$ProductionJar,
    [string]$ProbeJar = 'build/reobfProductionProbeJar/output.jar',
    [string]$Distances = '18,28',
    [string]$Concurrency = '128,256,512',
    [ValidateRange(1,10)][int]$Repeats = 1,
    [ValidateRange(0,60)][int]$AddedLoadMs = 0,
    [ValidateRange(0,3)][int]$SpeedAmplifier = 1,
    [ValidateRange(1,120)][int]$SubmissionSpanTicks = 1,
    [switch]$Vanilla,
    [ValidatePattern('^[a-z0-9-]+$')][string]$ComparisonMode,
    [switch]$ColdStart,
    [switch]$HandoffTrace,
    [ValidatePattern('^\d+(,\d+)*$')][string]$HandoffActors = '25,29,281,285',
    [ValidateRange(-1,511)][int]$PositionOffsetActor = -1,
    [ValidateRange(-0.1,0.1)][double]$PositionOffsetZ = 0,
    [ValidatePattern('^\.tmp/[a-z0-9-]+$')][string]$EvidenceRoot = '.tmp/navigation-dynamic-budget-production-20261014'
)
$ErrorActionPreference = 'Stop'
$taskWorkspace = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$taskRunPath = Join-Path $taskWorkspace ($EvidenceRoot + '/' + $Run)
if (Test-Path -LiteralPath $taskRunPath) { throw 'Fresh isolated run required' }
New-Item -Path (Join-Path $taskRunPath 'mods') -ItemType Directory -Force | Out-Null
$taskLibraries = (Resolve-Path -LiteralPath (Join-Path $taskWorkspace '.tmp/navigation-production-release-20261013/runtime/libraries')).Path
New-Item -Path (Join-Path $taskRunPath 'libraries') -ItemType Junction -Target $taskLibraries | Out-Null
foreach ($taskPair in @(
    @('run/eula.txt', 'eula.txt'),
    @('tools/benchmark/performance-server.properties', 'server.properties'),
    @($ProductionJar, 'mods/accelerated_navigation.jar'),
    @($ProbeJar, 'mods/production-probe.jar'))) {
    Copy-Item -LiteralPath (Join-Path $taskWorkspace $taskPair[0]) -Destination (Join-Path $taskRunPath $taskPair[1])
}
$taskJava = 'C:\Program Files\Java\jdk-17.0.18+8\bin\java.exe'
$env:MOD_CLASSES = $null
$env:MCP_MAPPINGS = $null
$env:JAVA_TOOL_OPTIONS = $null
$taskJvmArgs = @('-Xms512m', '-Xmx3072m', ('-Dan.production.output=' + $taskRunPath),
    '-Dan.production.performanceComparison=true',
    ('-Dan.production.performanceVanilla=' + $Vanilla.IsPresent.ToString().ToLowerInvariant()),
    ('-Dan.production.performanceDistances=' + $Distances),
    ('-Dan.production.performanceConcurrency=' + $Concurrency),
    ('-Dan.production.performanceRepeats=' + $Repeats),
    ('-Dan.production.performanceSpeedAmplifier=' + $SpeedAmplifier),
    ('-Dan.production.performanceAddedLoadNanos=' + ($AddedLoadMs * 1000000L)),
    ('-Dan.production.performanceSubmissionSpanTicks=' + $SubmissionSpanTicks),
    ('-Dan.production.performanceWarmup=' + (-not $ColdStart).ToString().ToLowerInvariant()),
    ('-Dan.production.performanceStartupTicks=' + $(if ($ColdStart) { 40 } else { 180 })),
    ('-Dan.production.handoffTrace=' + $HandoffTrace.IsPresent.ToString().ToLowerInvariant()),
    ('-Dan.production.handoffActors=' + $HandoffActors),
    ('-Dan.production.performancePositionOffsetActor=' + $PositionOffsetActor),
    ('-Dan.production.performancePositionOffsetZ=' + $PositionOffsetZ.ToString([Globalization.CultureInfo]::InvariantCulture)),
    '-Dan.production.performanceTimeoutTicks=1200',
    ('-Djdk.net.unixdomain.tmpdir=' + (Join-Path $taskRunPath 'absent-socket')),
    '-Dmixin.debug.export=true')
$taskArgsFile = Join-Path $taskRunPath 'jvm.args'
[IO.File]::WriteAllLines($taskArgsFile,
    @($taskJvmArgs | ForEach-Object { '"' + $_.Replace('\','/').Replace('"','\"') + '"' }),
    [Text.UTF8Encoding]::new($false))
$taskLaunch = @{
    schema='PRODUCTION_BUDGET_COMMON_TELEMETRY_RUN'; productionJar=$ProductionJar;
    comparisonMode=$ComparisonMode; vanilla=$Vanilla.IsPresent;
    startedAt=(Get-Date).ToUniversalTime().ToString('o');
    distances=$Distances; concurrency=$Concurrency; repeats=$Repeats; addedLoadMs=$AddedLoadMs;
    speedAmplifier=$SpeedAmplifier; java=$taskJava; heapMb=3072; seed=20261013;
    submissionSpanTicks=$SubmissionSpanTicks; coldStart=$ColdStart.IsPresent;
    handoffTrace=$HandoffTrace.IsPresent;
    handoffActors=$HandoffActors;
    positionOffsetActor=$PositionOffsetActor; positionOffsetZ=$PositionOffsetZ;
    propertiesSha256=(Get-FileHash -LiteralPath (Join-Path $taskRunPath 'server.properties')).Hash;
    mods=@(Get-ChildItem -LiteralPath (Join-Path $taskRunPath 'mods') -File | ForEach-Object {
        @{name=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}
    })
}
$taskLaunch | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $taskRunPath 'launch-environment.json') -Encoding utf8
$taskStarted=Get-Date
$taskEnvironmentBefore = @{
    measuredAt=$taskStarted.ToUniversalTime().ToString('o');
    os=Get-CimInstance Win32_OperatingSystem |
        Select-Object TotalVisibleMemorySize,FreePhysicalMemory;
    processors=@(Get-CimInstance Win32_Processor |
        Select-Object Name,NumberOfCores,NumberOfLogicalProcessors);
    otherJava=@(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
        Select-Object ProcessId,ParentProcessId,CommandLine)
}
$taskEnvironmentBefore | ConvertTo-Json -Depth 5 |
    Set-Content -LiteralPath (Join-Path $taskRunPath 'host-before.json') -Encoding utf8
$taskProcess=Start-Process -FilePath $taskJava -ArgumentList "@`"$taskArgsFile`" @libraries/net/minecraftforge/forge/1.20.1-47.3.0/win_args.txt --nogui" `
    -WindowStyle Hidden -WorkingDirectory $taskRunPath -PassThru `
    -RedirectStandardOutput (Join-Path $taskRunPath 'stdout.txt') -RedirectStandardError (Join-Path $taskRunPath 'stderr.txt')
$taskTimedOut=$false
while (-not $taskProcess.WaitForExit(1000)) {
    if (((Get-Date)-$taskStarted).TotalSeconds -gt 1500) {
        $taskTimedOut=$true; Stop-Process -Id $taskProcess.Id; $taskProcess.WaitForExit(); break
    }
}
$taskProcess.Refresh()
$taskExitCode = $taskProcess.ExitCode
if ($null -eq $taskExitCode) {
    $taskResultPath = Join-Path $taskRunPath 'performance-comparison.json'
    $taskShutdownPath = Join-Path $taskRunPath 'shutdown.json'
    if ((Test-Path -LiteralPath $taskResultPath) -and (Test-Path -LiteralPath $taskShutdownPath)) {
        $taskResultProbe = Get-Content -LiteralPath $taskResultPath -Raw | ConvertFrom-Json
        $taskShutdownProbe = Get-Content -LiteralPath $taskShutdownPath -Raw | ConvertFrom-Json
        if ($taskResultProbe.complete -and -not $taskResultProbe.failed -and $taskShutdownProbe.passed) {
            $taskExitCode = 0
        }
    }
}
@{exitCode=$taskExitCode;timedOut=$taskTimedOut;seconds=((Get-Date)-$taskStarted).TotalSeconds;
    pid=$taskProcess.Id;finishedAt=(Get-Date).ToUniversalTime().ToString('o')} |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskRunPath 'exit.json') -Encoding utf8
if ($taskTimedOut -or $taskExitCode -ne 0) { throw 'Production candidate failed; evidence retained' }
$taskResult=Get-Content -LiteralPath (Join-Path $taskRunPath 'performance-comparison.json') -Raw | ConvertFrom-Json
$taskTicks=Get-Content -LiteralPath (Join-Path $taskRunPath 'full-ticks.json') -Raw | ConvertFrom-Json
$taskDispatch=Get-Content -LiteralPath (Join-Path $taskRunPath 'dispatch-timings.json') -Raw | ConvertFrom-Json
$taskShutdown=Get-Content -LiteralPath (Join-Path $taskRunPath 'shutdown.json') -Raw | ConvertFrom-Json
if ($taskResult.failed -or -not $taskResult.complete -or $taskTicks.truncated -or $taskDispatch.truncated -or -not $taskShutdown.passed) {
    throw 'Incomplete candidate evidence'
}
Select-String -LiteralPath (Join-Path $taskRunPath 'stdout.txt') -Pattern 'PERF_READY|PERF_FINISH' |
    Select-Object -Last 4 | ForEach-Object { $_.Line }
@{complete=$taskResult.complete;trials=$taskResult.trials.Count;fullTickSamples=$taskTicks.samples.Count} | ConvertTo-Json
