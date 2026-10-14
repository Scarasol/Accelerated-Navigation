param(
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]+$')][string]$Run,
    [ValidateSet('fixed4','fixed8','fixed12','dynamic12')][string]$Mode = 'dynamic12',
    [ValidateSet('unchanged','grace1','grace2')][string]$Handoff = 'unchanged',
    [ValidateRange(0,60)][int]$AddedLoadMs = 0,
    [ValidatePattern('^\.tmp/[a-z0-9-]+$')][string]$EvidenceRoot = '.tmp/navigation-dynamic-budget-20261013',
    [switch]$Trace,
    [string]$Distances = '8,18,28',
    [string]$Concurrency = '128,256,512',
    [ValidateRange(1,10)][int]$Repeats = 2,
    [ValidateRange(100,1200)][int]$TimeoutTicks = 1200
)
$ErrorActionPreference = 'Stop'
$taskWorkspace = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$taskRoot = Join-Path $taskWorkspace $EvidenceRoot
$taskRunPath = Join-Path $taskRoot $Run
if (Test-Path -LiteralPath $taskRunPath) { throw 'Fresh isolated run name required' }
$taskFrozen = Join-Path $taskRoot 'frozen-mods'
if (-not (Test-Path -LiteralPath $taskFrozen)) {
    New-Item -Path $taskFrozen -ItemType Directory | Out-Null
    $taskPriorFrozen = Join-Path $taskWorkspace '.tmp/navigation-dynamic-budget-20261013/frozen-mods'
    if (Test-Path -LiteralPath (Join-Path $taskPriorFrozen 'accelerated_navigation.jar')) {
        Copy-Item -LiteralPath (Join-Path $taskPriorFrozen 'accelerated_navigation.jar') -Destination (Join-Path $taskFrozen 'accelerated_navigation.jar')
        Copy-Item -LiteralPath (Join-Path $taskPriorFrozen 'production-probe.jar') -Destination (Join-Path $taskFrozen 'production-probe.jar')
    } else {
        Copy-Item -LiteralPath (Join-Path $taskWorkspace 'build/libs/accelerated_navigation-0.1.0-all.jar') -Destination (Join-Path $taskFrozen 'accelerated_navigation.jar')
        Copy-Item -LiteralPath (Join-Path $taskWorkspace 'build/reobfProductionProbeJar/output.jar') -Destination (Join-Path $taskFrozen 'production-probe.jar')
    }
    Copy-Item -LiteralPath (Join-Path $taskWorkspace 'build/reobfBudgetPrototypeJar/output.jar') -Destination (Join-Path $taskFrozen 'budget-prototype.jar')
}
if ($Handoff -ne 'unchanged') {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $taskPrototypeArchive = [IO.Compression.ZipFile]::OpenRead((Join-Path $taskFrozen 'budget-prototype.jar'))
    try {
        $taskMixinReader = [IO.StreamReader]::new($taskPrototypeArchive.GetEntry('an_budget_prototype.mixins.json').Open())
        try { $taskMixinConfig = $taskMixinReader.ReadToEnd() | ConvertFrom-Json }
        finally { $taskMixinReader.Dispose() }
        if ($taskMixinConfig.mixins -notcontains 'BudgetHandoffTimingMixin') {
            throw 'Frozen prototype lacks handoff experiment; select a fresh EvidenceRoot'
        }
    } finally { $taskPrototypeArchive.Dispose() }
}
New-Item -Path (Join-Path $taskRunPath 'mods') -ItemType Directory -Force | Out-Null
Get-ChildItem -LiteralPath $taskFrozen -File | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $taskRunPath 'mods')
}
$taskLibraries = (Resolve-Path -LiteralPath (Join-Path $taskWorkspace '.tmp/navigation-production-release-20261013/runtime/libraries')).Path
New-Item -Path (Join-Path $taskRunPath 'libraries') -ItemType Junction -Target $taskLibraries | Out-Null
Copy-Item -LiteralPath (Join-Path $taskWorkspace 'run/eula.txt') -Destination (Join-Path $taskRunPath 'eula.txt')
Copy-Item -LiteralPath (Join-Path $taskWorkspace 'tools/benchmark/performance-server.properties') -Destination (Join-Path $taskRunPath 'server.properties')
$env:MOD_CLASSES = $null
$env:MCP_MAPPINGS = $null
$env:JAVA_TOOL_OPTIONS = $null
$taskJava = 'C:\Program Files\Java\jdk-17.0.18+8\bin\java.exe'
$taskJvmArgs = @(
    '-Xms512m', '-Xmx3072m',
    ('-Dan.production.output=' + $taskRunPath),
    '-Dan.production.performanceComparison=true',
    '-Dan.production.performanceVanilla=false',
    ('-Dan.production.performanceDistances=' + $Distances),
    ('-Dan.production.performanceConcurrency=' + $Concurrency),
    ('-Dan.production.performanceRepeats=' + $Repeats),
    ('-Dan.production.performanceTimeoutTicks=' + $TimeoutTicks),
    ('-Dan.budget.mode=' + $Mode),
    ('-Dan.budget.handoff=' + $Handoff),
    ('-Dan.budget.addedLoadNanos=' + ($AddedLoadMs * 1000000L)),
    ('-Dan.budget.trace=' + $Trace.IsPresent.ToString().ToLowerInvariant()),
    ('-Djdk.net.unixdomain.tmpdir=' + (Join-Path $taskRunPath 'absent-socket'))
)
$taskArgsFile = Join-Path $taskRunPath 'jvm.args'
[IO.File]::WriteAllLines($taskArgsFile,
    @($taskJvmArgs | ForEach-Object { '"' + $_.Replace('\','/').Replace('"','\"') + '"' }),
    [Text.UTF8Encoding]::new($false))
$taskLaunch = @{
    schema = 'ISOLATED_DYNAMIC_BUDGET_RUN'
    mode = $Mode
    handoff = $Handoff
    addedLoadMs = $AddedLoadMs
    trace = $Trace.IsPresent
    java = $taskJava
    seed = 20261013
    distances = $Distances
    concurrency = $Concurrency
    repeats = $Repeats
    heapMb = 3072
    mods = @(Get-ChildItem -LiteralPath (Join-Path $taskRunPath 'mods') -File | ForEach-Object {
        @{ name=$_.Name; sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
    })
    propertiesSha256 = (Get-FileHash -LiteralPath (Join-Path $taskRunPath 'server.properties') -Algorithm SHA256).Hash
}
$taskLaunch | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $taskRunPath 'launch-environment.json') -Encoding utf8
$taskStarted = Get-Date
$taskProcess = Start-Process -FilePath $taskJava -ArgumentList "@`"$taskArgsFile`" @libraries/net/minecraftforge/forge/1.20.1-47.3.0/win_args.txt --nogui" `
    -WindowStyle Hidden -WorkingDirectory $taskRunPath -PassThru `
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
Select-String -LiteralPath (Join-Path $taskRunPath 'stdout.txt') -Pattern 'PERF_READY|PERF_FINISH|BUDGET_PROTOTYPE_FINISH' |
    Select-Object -Last 5 | ForEach-Object { $_.Line }
if ($taskTimedOut -or $taskProcess.ExitCode -ne 0) { throw 'Prototype server failed; evidence retained' }
$taskResult = Get-Content -LiteralPath (Join-Path $taskRunPath 'performance-comparison.json') -Raw | ConvertFrom-Json
if ($taskResult.failed -or -not $taskResult.complete) { throw 'Prototype benchmark incomplete; evidence retained' }
if (-not (Test-Path -LiteralPath (Join-Path $taskRunPath 'budget-ticks.json'))) { throw 'No complete tick telemetry' }
$taskBudget = Get-Content -LiteralPath (Join-Path $taskRunPath 'budget-ticks.json') -Raw | ConvertFrom-Json
if ($taskBudget.mode -ne $Mode -or ($Handoff -ne 'unchanged' -and $taskBudget.handoff -ne $Handoff)) {
    throw 'Executed experiment differs from launch request; evidence retained'
}
@{ mode=$Mode; failed=$taskResult.failed; complete=$taskResult.complete; trials=$taskResult.trials.Count } | ConvertTo-Json
