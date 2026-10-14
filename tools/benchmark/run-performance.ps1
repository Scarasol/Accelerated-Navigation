param(
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]+$')][string]$Run,
    [switch]$Vanilla,
    [string]$Distances = '8,18,28',
    [string]$Concurrency = '128,256,512',
    [ValidateRange(1,10)][int]$Repeats = 2,
    [ValidateRange(512,8192)][int]$HeapMb = 3072,
    [ValidateRange(100,1200)][int]$TimeoutTicks = 1200
)
$ErrorActionPreference = 'Stop'
$taskWorkspace = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$taskJava = 'C:\Program Files\Java\jdk-17.0.18+8\bin\java.exe'
$taskRoot = Join-Path $taskWorkspace '.tmp/navigation-performance-high-20261013'
$taskRunPath = Join-Path $taskRoot $Run
if (Test-Path -LiteralPath $taskRunPath) { throw 'Use a fresh benchmark run name' }
New-Item -Path (Join-Path $taskRunPath 'mods') -ItemType Directory -Force | Out-Null
$taskLibraries = (Resolve-Path -LiteralPath (Join-Path $taskWorkspace '.tmp/navigation-production-release-20261013/runtime/libraries')).Path
New-Item -Path (Join-Path $taskRunPath 'libraries') -ItemType Junction -Target $taskLibraries | Out-Null
foreach ($taskPair in @(
    @('run/eula.txt', 'eula.txt'),
    @('tools/benchmark/performance-server.properties', 'server.properties'),
    @('build/libs/accelerated_navigation-0.1.0-all.jar', 'mods/accelerated_navigation.jar'),
    @('build/reobfProductionProbeJar/output.jar', 'mods/production-probe.jar')
)) {
    Copy-Item -LiteralPath (Join-Path $taskWorkspace $taskPair[0]) -Destination (Join-Path $taskRunPath $taskPair[1])
}
$env:MOD_CLASSES = $null
$env:MCP_MAPPINGS = $null
$env:JAVA_TOOL_OPTIONS = $null
$taskJvmArgs = @(
    '-Xms512m', ('-Xmx' + $HeapMb + 'm'),
    ('-Dan.production.output=' + $taskRunPath),
    '-Dan.production.performanceComparison=true',
    ('-Dan.production.performanceVanilla=' + $Vanilla.IsPresent.ToString().ToLowerInvariant()),
    ('-Dan.production.performanceDistances=' + $Distances),
    ('-Dan.production.performanceConcurrency=' + $Concurrency),
    ('-Dan.production.performanceRepeats=' + $Repeats),
    ('-Dan.production.performanceTimeoutTicks=' + $TimeoutTicks),
    ('-Djdk.net.unixdomain.tmpdir=' + (Join-Path $taskRunPath 'absent-socket'))
)
$taskArgsFile = Join-Path $taskRunPath 'jvm.args'
[IO.File]::WriteAllLines($taskArgsFile,
    @($taskJvmArgs | ForEach-Object { '"' + $_.Replace('\','/').Replace('"','\"') + '"' }),
    [Text.UTF8Encoding]::new($false))
$taskLaunch = @{
    variant = $(if ($Vanilla) {'vanilla-same-runtime'} else {'accelerated'})
    seed = 20261013
    java = $taskJava
    distances = $Distances
    concurrency = $Concurrency
    repeats = $Repeats
    heapMb = $HeapMb
    mods = @(Get-ChildItem -LiteralPath (Join-Path $taskRunPath 'mods') -File | ForEach-Object {
        @{ name=$_.Name; sha256=(Get-FileHash -LiteralPath $_.FullName).Hash }
    })
    propertiesSha256 = (Get-FileHash -LiteralPath (Join-Path $taskRunPath 'server.properties')).Hash
    probeSourceSha256 = (Get-FileHash -LiteralPath (Join-Path $taskWorkspace 'src/test/java/an/production/PerformanceComparisonProbe.java')).Hash
}
$taskLaunch | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $taskRunPath 'launch-environment.json') -Encoding utf8
$taskArguments = "@`"$taskArgsFile`" @libraries/net/minecraftforge/forge/1.20.1-47.3.0/win_args.txt --nogui"
$taskStarted = Get-Date
$taskProcess = Start-Process -FilePath $taskJava -ArgumentList $taskArguments -WindowStyle Hidden -WorkingDirectory $taskRunPath -PassThru `
    -RedirectStandardOutput (Join-Path $taskRunPath 'stdout.txt') -RedirectStandardError (Join-Path $taskRunPath 'stderr.txt')
$taskTimedOut = $false
while (-not $taskProcess.WaitForExit(1000)) {
    if (((Get-Date) - $taskStarted).TotalSeconds -gt 1500) {
        $taskTimedOut = $true
        Stop-Process -Id $taskProcess.Id
        $taskProcess.WaitForExit()
        break
    }
}
@{ exitCode=$taskProcess.ExitCode; timedOut=$taskTimedOut; seconds=((Get-Date)-$taskStarted).TotalSeconds; pid=$taskProcess.Id } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskRunPath 'exit.json') -Encoding utf8
Select-String -LiteralPath (Join-Path $taskRunPath 'stdout.txt') -Pattern 'PERF_READY|PERF_FINISH' | Select-Object -Last 5 | ForEach-Object { $_.Line }
if ($taskTimedOut -or $taskProcess.ExitCode -ne 0) { throw 'Server failed; evidence retained' }
$taskResult = Get-Content -LiteralPath (Join-Path $taskRunPath 'performance-comparison.json') -Raw | ConvertFrom-Json
@{ failed=$taskResult.failed; complete=$taskResult.complete; trials=$taskResult.trials.Count; failure=$taskResult.failure } | ConvertTo-Json
if ($taskResult.failed -or -not $taskResult.complete) { throw 'Benchmark incomplete; evidence retained' }
