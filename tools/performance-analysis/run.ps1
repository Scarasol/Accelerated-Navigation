param(
    [Parameter(Mandatory=$true)][string]$Run,
    [Parameter(Mandatory=$true)][string]$Build,
    [Parameter(Mandatory=$true)][string]$OutputRoot,
    [ValidateSet('template','qualification','formal')][string]$Mode='formal',
    [ValidateSet('baseline','candidate','native')][string]$Variant='baseline',
    [switch]$Diagnostic
)
$ErrorActionPreference='Stop'
if ($Run -notmatch '^[a-z0-9-]+$') { throw 'Unsafe run name' }
$referenceRepo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$referenceFrozen=Join-Path $referenceRepo '.tmp/current-performance-reference-20261016'
$referenceBuild=(Resolve-Path -LiteralPath $Build).Path
$campaignRoot=[IO.Path]::GetFullPath($OutputRoot,$referenceRepo)
$referenceRun=Join-Path $campaignRoot ('runs/'+$Run)
if (Test-Path -LiteralPath $referenceRun) { throw 'Preserve previous run' }
New-Item -ItemType Directory -Path (Join-Path $referenceRun 'mods') -Force | Out-Null
$referenceLibraries=(Resolve-Path -LiteralPath (Join-Path $referenceRepo '.tmp/navigation-production-release-20261013/runtime/libraries')).Path
New-Item -ItemType Junction -Path (Join-Path $referenceRun 'libraries') -Target $referenceLibraries | Out-Null
Copy-Item -LiteralPath (Join-Path $referenceRepo 'run/eula.txt') -Destination (Join-Path $referenceRun 'eula.txt')
Copy-Item -LiteralPath (Join-Path $referenceFrozen 'frozen/server.properties') -Destination (Join-Path $referenceRun 'server.properties')
Copy-Item -LiteralPath (Join-Path $referenceBuild 'artifacts/production.jar') -Destination (Join-Path $referenceRun 'mods/accelerated_navigation.jar')
Copy-Item -LiteralPath (Join-Path $referenceFrozen 'artifacts/reference-probe-frozen.jar') -Destination (Join-Path $referenceRun 'mods/reference-probe.jar')
if ($Mode -ne 'template') {
    $templateRoot=Join-Path $referenceFrozen 'runs/template/world'
    if (-not (Test-Path -LiteralPath (Join-Path $referenceFrozen 'template-manifest.json'))) { throw 'Missing sealed template' }
    Copy-Item -LiteralPath $templateRoot -Destination (Join-Path $referenceRun 'world') -Recurse
}
$referenceIdleStart=[DateTime]::UtcNow
$referenceQuietSince=$null
$referenceWaitObservations=@()
while ($true) {
    $others=@(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" | Select-Object ProcessId,Name)
    if ($others.Count -gt 0) {
        $referenceQuietSince=$null
        $referenceWaitObservations+=@{at=[DateTime]::UtcNow.ToString('o');processes=$others}
    } elseif ($null -eq $referenceQuietSince) { $referenceQuietSince=[DateTime]::UtcNow }
    elseif (([DateTime]::UtcNow-$referenceQuietSince).TotalSeconds -ge 10) { break }
    if (([DateTime]::UtcNow-$referenceIdleStart).TotalSeconds -gt 1800) { throw 'No Java quiet window' }
    Start-Sleep -Seconds 1
}
@{startedUtc=$referenceIdleStart.ToString('o');quietSinceUtc=$referenceQuietSince.ToString('o');
    endedUtc=[DateTime]::UtcNow.ToString('o');observations=$referenceWaitObservations} |
    ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $referenceRun 'host-wait.json') -Encoding utf8
$referenceArgs=@('-Xms2048m','-Xmx2048m','-XX:+UseG1GC',
    ('-Dan.production.output='+$referenceRun),
    ('-Dan.production.performanceVanilla='+($Variant -eq 'native').ToString().ToLowerInvariant()),
    '-Dan.production.performanceDistances=8,28','-Dan.production.performanceConcurrency=512',
    '-Dan.production.performanceRepeats=1','-Dan.production.performanceWarmup=true',
    '-Dan.production.performanceTimeoutTicks=1200','-Dan.production.performanceSpeedAmplifier=1',
    '-Dan.production.performanceSubmissionSpanTicks=1','-Dan.production.performanceAddedLoadNanos=0',
    ('-Dan.reference.template='+($Mode -eq 'template').ToString().ToLowerInvariant()),
    ('-Dan.reference.useTemplate='+($Mode -ne 'template').ToString().ToLowerInvariant()),
    ('-Djdk.net.unixdomain.tmpdir='+(Join-Path $referenceRun 'absent-socket')))
if ($Mode -eq 'qualification') {
    $referenceArgs=@($referenceArgs | Where-Object {$_ -notmatch 'performanceDistances=|performanceConcurrency=|performanceWarmup='})
    $referenceArgs+=@('-Dan.production.performanceDistances=8','-Dan.production.performanceConcurrency=32','-Dan.production.performanceWarmup=false')
}
if ($Diagnostic) {
    $referenceArgs+=('-XX:StartFlightRecording=settings=profile,dumponexit=true,filename='+(Join-Path $referenceRun 'server.jfr').Replace('\','/'))
}
$referenceArgFile=Join-Path $referenceRun 'jvm.args'
[IO.File]::WriteAllLines($referenceArgFile,@($referenceArgs | ForEach-Object {'"'+$_.Replace('\','/')+'"'}),[Text.UTF8Encoding]::new($false))
$referenceLaunch=[ordered]@{
    run=$Run;mode=$Mode;variant=$Variant;diagnostic=[bool]$Diagnostic
    java='C:\Program Files\Java\jdk-17.0.18+8\bin\java.exe'
    jvmArgs=$referenceArgs;forgeArgs='@libraries/net/minecraftforge/forge/1.20.1-47.3.0/win_args.txt --nogui'
    mods=@(Get-ChildItem -LiteralPath (Join-Path $referenceRun 'mods') -File | ForEach-Object {
        @{name=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}
    })
    os=([Environment]::OSVersion.VersionString)
    processors=[Environment]::ProcessorCount
    cpu=@(Get-CimInstance Win32_Processor | Select-Object Name,NumberOfCores,NumberOfLogicalProcessors)
    memory=@(Get-CimInstance Win32_OperatingSystem | Select-Object TotalVisibleMemorySize,FreePhysicalMemory)
    templateManifestSha256=if($Mode -ne 'template'){(Get-FileHash -LiteralPath (Join-Path $referenceFrozen 'template-manifest.json')).Hash.ToLowerInvariant()}else{''}
}
$referenceLaunch | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $referenceRun 'launch.json') -Encoding utf8
$env:MOD_CLASSES=$null
$env:MCP_MAPPINGS=$null
$env:JAVA_TOOL_OPTIONS=$null
$env:GRADLE_OPTS=$null
$referenceStart=[DateTime]::UtcNow
$referenceProcess=Start-Process -FilePath $referenceLaunch.java `
    -ArgumentList "@`"$referenceArgFile`" @libraries/net/minecraftforge/forge/1.20.1-47.3.0/win_args.txt --nogui" `
    -WorkingDirectory $referenceRun -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $referenceRun 'stdout.txt') -RedirectStandardError (Join-Path $referenceRun 'stderr.txt')
Write-Output ("REFERENCE_STARTED "+$Run+" pid="+$referenceProcess.Id+" utc="+$referenceStart.ToString('o'))
$referenceTimedOut=$false
$referenceInterference=@()
while (-not $referenceProcess.WaitForExit(1000)) {
    $others=@(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
        Where-Object {$_.ProcessId -ne $referenceProcess.Id} | Select-Object ProcessId,Name)
    if ($others.Count -gt 0) { $referenceInterference+=@{atUtc=[DateTime]::UtcNow.ToString('o');processes=$others} }
    if (([DateTime]::UtcNow-$referenceStart).TotalSeconds -gt 1100) {
        $referenceTimedOut=$true
        Stop-Process -Id $referenceProcess.Id
        $referenceProcess.WaitForExit()
        break
    }
}
$referenceProcess.Refresh()
$referenceExit=@{exitCode=$referenceProcess.ExitCode;timedOut=$referenceTimedOut;pid=$referenceProcess.Id;
    startedUtc=$referenceStart.ToString('o');endedUtc=[DateTime]::UtcNow.ToString('o');
    seconds=([DateTime]::UtcNow-$referenceStart).TotalSeconds;concurrentJava=$referenceInterference}
$referenceExit | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $referenceRun 'exit.json') -Encoding utf8
if ($referenceTimedOut -or $referenceProcess.ExitCode -ne 0) {
    Get-Content -LiteralPath (Join-Path $referenceRun 'stdout.txt') -Tail 45
    throw ('Server failed: '+$Run)
}
if ($Mode -eq 'template') {
    $templateReady=Get-Content -LiteralPath (Join-Path $referenceRun 'template-ready.json') -Raw | ConvertFrom-Json
    if (-not $templateReady.built -or $templateReady.ticksAfterBuild -lt 40) { throw 'Incomplete template' }
    $templateWorld=Join-Path $referenceRun 'world'
    @{files=@(Get-ChildItem -LiteralPath $templateWorld -File -Recurse | Sort-Object FullName | ForEach-Object {
        @{path=$_.FullName.Substring($templateWorld.Length+1).Replace('\','/');bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}
    })} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $campaignRoot 'template-manifest.json') -Encoding utf8
} else {
    $result=Get-Content -LiteralPath (Join-Path $referenceRun 'performance-comparison.json') -Raw | ConvertFrom-Json
    $ticks=Get-Content -LiteralPath (Join-Path $referenceRun 'full-ticks.json') -Raw | ConvertFrom-Json
    $shutdown=Get-Content -LiteralPath (Join-Path $referenceRun 'shutdown.json') -Raw | ConvertFrom-Json
    if ($result.failed -or -not $result.complete -or $ticks.truncated -or -not $shutdown.passed) { throw 'Technical validity check failed' }
    foreach ($trial in $result.trials) {
        if ($trial.batchStartNanos-$trial.serverStartedNanos -lt 30000000000L -or
            $trial.batchStartNanos-$trial.sceneReadyNanos -lt 30000000000L) { throw '30s gate failed' }
        if (@($ticks.samples | Where-Object {$_.serverTick -ge $trial.serverSubmitTick -and $_.serverTick -le $trial.serverEndTick}).Count -ne ($trial.serverEndTick-$trial.serverSubmitTick+1)) { throw 'Missing full-tick samples' }
    }
    if ($referenceInterference.Count -gt 0) { throw 'External Java interference; preserve this run and rerun with a new suffix' }
}
Write-Output ("REFERENCE_FINISHED "+$Run+" seconds="+[Math]::Round($referenceExit.seconds,2))
