param(
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]+$')][string]$Run,
    [ValidateSet('lifecycle','speed','boundaries','wait','tick-load','replay','shared','cold')][string]$Probe = 'lifecycle',
    [string]$ProductionJar = 'build/libs/accelerated_navigation-0.1.0-all.jar',
    [string]$ProbeJar = 'build/reobfProductionProbeJar/output.jar',
    [string]$ReplaySource,
    [string]$ReplayControl,
    [ValidatePattern('^\.tmp/[a-z0-9-]+$')][string]$EvidenceRoot = '.tmp/navigation-dynamic-budget-production-20261014',
    [ValidateSet('same','base','super')][string]$ColdRoute = 'same',
    [switch]$ColdStages
)
$ErrorActionPreference = 'Stop'
$taskWorkspace = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$taskRunPath = Join-Path $taskWorkspace ($EvidenceRoot + '/' + $Run)
if (Test-Path -LiteralPath $taskRunPath) { throw 'Use a fresh isolated verification directory' }
New-Item -Path (Join-Path $taskRunPath 'mods') -ItemType Directory -Force | Out-Null
$taskLibraries = (Resolve-Path -LiteralPath (Join-Path $taskWorkspace '.tmp/navigation-production-release-20261013/runtime/libraries')).Path
New-Item -Path (Join-Path $taskRunPath 'libraries') -ItemType Junction -Target $taskLibraries | Out-Null
$taskInputs = @()
if ($Probe -in @('lifecycle','speed','boundaries')) {
    New-Item -Path (Join-Path $taskRunPath 'world/region') -ItemType Directory -Force | Out-Null
    $taskFrozen = Get-Content -LiteralPath (Join-Path $taskWorkspace '.tmp/segment-execution-validation-20261012/world-inputs-server-7.json') -Raw | ConvertFrom-Json
    foreach ($taskRecord in $taskFrozen) {
        if ($taskRecord.source) {
            $taskSource = $taskRecord.source.path
            $taskExpected = $taskRecord.source.sha256
            $taskTarget = Join-Path $taskRunPath ('world/region/' + (Split-Path -Path $taskSource -Leaf))
        } else {
            $taskSource = $taskRecord.sourceMetadata.path
            $taskExpected = (Get-FileHash -LiteralPath $taskSource).Hash
            $taskTarget = Join-Path $taskRunPath 'world/level.dat'
        }
        if ((Get-FileHash -LiteralPath $taskSource).Hash -ne $taskExpected) { throw 'Frozen source changed' }
        Copy-Item -LiteralPath $taskSource -Destination $taskTarget
        $taskInputs += @{source=$taskSource;copy=$taskTarget;sha256=$taskExpected}
    }
    $taskCompileCp = (Get-Content -LiteralPath (Join-Path $taskWorkspace '.tmp/store-level5-integration-20261011/compile-classpath.txt') -Raw).Trim()
    & 'C:\Program Files\Java\jdk-17.0.18+8\bin\java.exe' '-cp' ((Join-Path $taskWorkspace '.tmp/segment-landing-fix-20261012/build-guarded/classes') + ';' + $taskCompileCp) 'an.landing.Metadata' (Join-Path $taskRunPath 'world/level.dat') *> (Join-Path $taskRunPath 'metadata.log')
    if ($LASTEXITCODE -ne 0) { throw 'Isolated metadata normalization failed' }
    Copy-Item -LiteralPath (Join-Path $taskWorkspace '.tmp/segment-execution-validation-20261012/stages.json') -Destination (Join-Path $taskRunPath 'stages.json')
    Copy-Item -LiteralPath (Join-Path $taskWorkspace '.tmp/navigation-command-entity-20261013/server-speed/mods/lostcities.jar') -Destination (Join-Path $taskRunPath 'mods/lostcities.jar')
}
$taskProperties = if ($Probe -in @('shared','cold')) {
    'tools/benchmark/shared-server.properties'
} elseif ($Probe -in @('lifecycle','speed','boundaries')) {
    '.tmp/segment-landing-fix-20261012/resources/server.properties'
} else { 'tools/benchmark/performance-server.properties' }
foreach ($taskPair in @(@('run/eula.txt','eula.txt'), @($taskProperties,'server.properties'),
        @($ProductionJar,'mods/accelerated_navigation.jar'),
        @($ProbeJar,'mods/production-probe.jar'))) {
    Copy-Item -LiteralPath (Join-Path $taskWorkspace $taskPair[0]) -Destination (Join-Path $taskRunPath $taskPair[1])
}
$taskOptions = @{
    lifecycle='-Dan.production.routeStart=0'; speed='-Dan.production.speedDiagnosis=true';
    boundaries='-Dan.production.handoffBoundaries=true'; wait='-Dan.production.waitBoundaries=true';
    'tick-load'='-Dan.production.tickBoundaryLoad=true'; replay='-Dan.production.partialReplay=true';
    shared='-Dan.production.sharedTopology=true'
    cold='-Dan.production.coldTopology=true'
}
$env:MOD_CLASSES = $null
$env:MCP_MAPPINGS = $null
$env:JAVA_TOOL_OPTIONS = $null
$taskJvmArgs = @('-Xms256m','-Xmx1536m', ('-Dan.production.output=' + $taskRunPath),
    $taskOptions[$Probe], ('-Djdk.net.unixdomain.tmpdir=' + (Join-Path $taskRunPath 'absent-socket')),
    '-Dmixin.debug.export=true','-Dmixin.debug.countInjections=true')
if ($ReplaySource) {
    if ($Probe -ne 'replay' -or -not $ReplayControl) { throw 'Actual replay needs both trace inputs and replay mode' }
    $taskJvmArgs += '-Dan.production.partialReplaySource=' + (Resolve-Path -LiteralPath $ReplaySource).Path
    $taskJvmArgs += '-Dan.production.partialReplayControl=' + (Resolve-Path -LiteralPath $ReplayControl).Path
}
if ($Probe -eq 'cold') {
    $taskJvmArgs += '-Dan.production.coldRoute=' + $ColdRoute
    if ($ColdStages) { $taskJvmArgs += '-Dan.production.coldStages=true' }
}
$taskArgsFile = Join-Path $taskRunPath 'jvm.args'
[IO.File]::WriteAllLines($taskArgsFile,
    @($taskJvmArgs | ForEach-Object { '"' + $_.Replace('\','/').Replace('"','\"') + '"' }),
    [Text.UTF8Encoding]::new($false))
@{schema='PRODUCTION_CHECKS_V1';probe=$Probe;productionJar=$ProductionJar;coldRoute=$ColdRoute;coldStages=[bool]$ColdStages;mods=@(
    Get-ChildItem -LiteralPath (Join-Path $taskRunPath 'mods') -File | ForEach-Object {
        @{name=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}
    })} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $taskRunPath 'launch-environment.json') -Encoding utf8
$taskInputs | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $taskRunPath 'world-inputs.json') -Encoding utf8
$taskStarted = Get-Date
$taskProcess = Start-Process -FilePath 'C:\Program Files\Java\jdk-17.0.18+8\bin\java.exe' -ArgumentList "@`"$taskArgsFile`" @libraries/net/minecraftforge/forge/1.20.1-47.3.0/win_args.txt --nogui" -WindowStyle Hidden -WorkingDirectory $taskRunPath -PassThru -RedirectStandardOutput (Join-Path $taskRunPath 'stdout.txt') -RedirectStandardError (Join-Path $taskRunPath 'stderr.txt')
$taskTimedOut = $false
while (-not $taskProcess.WaitForExit(1000)) {
    if (((Get-Date)-$taskStarted).TotalSeconds -gt 1500) {
        $taskTimedOut=$true; Stop-Process -Id $taskProcess.Id; $taskProcess.WaitForExit(); break
    }
}
@{exitCode=$taskProcess.ExitCode;timedOut=$taskTimedOut;seconds=((Get-Date)-$taskStarted).TotalSeconds;pid=$taskProcess.Id;startedAt=$taskStarted.ToUniversalTime().ToString('o');exitedAt=(Get-Date).ToUniversalTime().ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskRunPath 'exit.json') -Encoding utf8
foreach ($taskInput in $taskInputs) {
    if ((Get-FileHash -LiteralPath $taskInput.source).Hash -ne $taskInput.sha256) { throw 'Source input changed' }
}
if ($taskTimedOut -or $taskProcess.ExitCode -ne 0) { throw 'Verification execution failed; evidence retained' }
$taskResultName = switch ($Probe) {
    lifecycle {'result.json'}; wait {'wait-boundaries.json'}; 'tick-load' {'tick-boundary-load.json'}
    replay {'partial-replay.json'}; shared {'shared-production.json'}; cold {'cold-topology.json'}; default {'diagnosis.json'}
}
$taskResult = Get-Content -LiteralPath (Join-Path $taskRunPath $taskResultName) -Raw | ConvertFrom-Json
if ($taskResult.failed) { throw ('Verification failed: ' + $taskResult.failure) }
@{probe=$Probe;failed=$taskResult.failed;checks=$taskResult.checks.Count;trials=$taskResult.trials.Count} | ConvertTo-Json
