param(
    [Parameter(Mandatory)][string]$Build,
    [Parameter(Mandatory)][string]$Output,
    [ValidateSet('single','burst','supplement','coverage','failures','final-window','short-native')][string]$Suite='single',
    [ValidatePattern('^[a-z0-9,-]*$')][string]$Scene='',
    [switch]$Native,
    [switch]$NoTrace,
    [switch]$Replay,
    [switch]$Jfr,
    [switch]$OmitObservations,
    [switch]$TailChecks,
    [ValidateSet('none','cancel','replace')][string]$TailAction='none',
    [switch]$CityWorld,
    [ValidateSet('DEBUG','TRACE')][string]$NavigationLogLevel='DEBUG'
)
$ErrorActionPreference='Stop'
if($OmitObservations -and ($Suite -ne 'coverage' -or -not $NoTrace -or $Jfr)){
    throw 'OmitObservations requires coverage, NoTrace and no JFR'
}
if($TailChecks -and ($Suite -ne 'coverage' -or $NoTrace)){
    throw 'TailChecks requires traced coverage'
}
if($TailAction -ne 'none' -and ($Suite -ne 'coverage' -or $NoTrace -or $TailChecks)){
    throw 'TailAction requires traced coverage without TailChecks'
}
if($CityWorld -and $Suite -ne 'short-native'){throw 'CityWorld requires short-native'}
if($Replay -and ($Suite -ne 'failures' -or -not $NoTrace -or $Native)){
    throw 'Counterfactual replay requires failures, NoTrace and the accelerated backend'
}
$traceRepo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$traceBuild=(Resolve-Path -LiteralPath $Build).Path
$traceRun=[IO.Path]::GetFullPath($Output,$traceRepo)
if(Test-Path -LiteralPath $traceRun){throw 'Preserve existing run'}
if((Get-PSDrive -Name D).Free -lt 3GB){throw 'Less than 3GiB free'}
New-Item -ItemType Directory -Path "$traceRun/mods" -Force|Out-Null
$traceRuntime=Join-Path $traceRepo '.tmp/navigation-production-release-20261013/runtime/libraries'
New-Item -ItemType Junction -Path "$traceRun/libraries" -Target $traceRuntime|Out-Null
Copy-Item -LiteralPath "$traceRepo/run/eula.txt" -Destination "$traceRun/eula.txt"
Copy-Item -LiteralPath "$traceRepo/.tmp/current-performance-reference-20261016/frozen/server.properties" -Destination "$traceRun/server.properties"
if($Suite -eq 'coverage'){
    $traceProperties=[IO.File]::ReadAllText("$traceRun/server.properties")
    if(-not $traceProperties.Contains('spawn-npcs=false')){throw 'Expected explicit NPC policy'}
    [IO.File]::WriteAllText("$traceRun/server.properties",$traceProperties.Replace('spawn-npcs=false','spawn-npcs=true'),
        [Text.UTF8Encoding]::new($false))
}
Copy-Item -LiteralPath "$traceRun/server.properties" -Destination "$traceRun/launch-server.properties"
$traceWorldInputs=@()
if($CityWorld){
    New-Item -ItemType Directory -Path "$traceRun/world/region" -Force|Out-Null
    $traceCityInputs=Get-Content -LiteralPath "$traceRepo/.tmp/segment-execution-validation-20261012/world-inputs-server-7.json" -Raw|ConvertFrom-Json
    foreach($traceInput in $traceCityInputs){
        if($traceInput.source){
            $traceSource=$traceInput.source.path
            $traceHash=(Get-FileHash -LiteralPath $traceSource).Hash.ToLowerInvariant()
            if($traceHash -ne $traceInput.source.sha256){throw 'Frozen city region changed'}
            Copy-Item -LiteralPath $traceSource -Destination "$traceRun/world/region"
        }else{
            $traceSource=$traceInput.sourceMetadata.path
            $traceHash=(Get-FileHash -LiteralPath $traceSource).Hash.ToLowerInvariant()
            Copy-Item -LiteralPath $traceSource -Destination "$traceRun/world/level.dat"
        }
        $traceWorldInputs+=@{path=$traceSource;sha256=$traceHash}
    }
    Copy-Item -LiteralPath "$traceRepo/.tmp/navigation-command-entity-20261013/server-speed/mods/lostcities.jar" -Destination "$traceRun/mods/lostcities.jar"
    $traceMetadataCp=(Get-Content -LiteralPath "$traceRepo/.tmp/store-level5-integration-20261011/compile-classpath.txt" -Raw).Trim()
    & 'C:\Program Files\Java\jdk-17.0.18+8\bin\java.exe' -cp ("$traceRepo/.tmp/segment-landing-fix-20261012/build-guarded/classes;"+$traceMetadataCp) an.landing.Metadata "$traceRun/world/level.dat" *> "$traceRun/metadata.log"
    if($LASTEXITCODE){throw 'City metadata normalization failed'}
    $traceWorldInputs+=@{path='normalized-level.dat';sha256=(Get-FileHash "$traceRun/world/level.dat").Hash.ToLowerInvariant()}
}else{
    Copy-Item -LiteralPath "$traceRepo/.tmp/current-performance-reference-20261016/runs/template/world" -Destination "$traceRun/world" -Recurse
}
Copy-Item -LiteralPath "$traceBuild/artifacts/production.jar" -Destination "$traceRun/mods/accelerated_navigation.jar"
Copy-Item -LiteralPath "$traceBuild/artifacts/timeline-probe.jar" -Destination "$traceRun/mods/timeline-probe.jar"
$traceJava='C:\Program Files\Java\jdk-17.0.18+8\bin'
$traceIdle=[Collections.Generic.List[object]]::new()
$traceQuiet=$null
$traceWait=[DateTime]::UtcNow
while($true){
    $traceOthers=@(Get-Process -Name java,javaw -ErrorAction SilentlyContinue|Select-Object Id,ProcessName)
    $traceNow=[DateTime]::UtcNow
    $traceIdle.Add(@{utc=$traceNow.ToString('o');otherJava=$traceOthers})
    if($traceOthers.Count){$traceQuiet=$null}
    elseif($null -eq $traceQuiet){$traceQuiet=$traceNow}
    elseif(($traceNow-$traceQuiet).TotalSeconds -ge 10){break}
    if(($traceNow-$traceWait).TotalMinutes -gt 10){throw 'External Java remained active; no process stopped'}
    Start-Sleep -Seconds 1
}
$traceIdle.ToArray()|ConvertTo-Json -Depth 6|Set-Content -LiteralPath "$traceRun/host-wait.json" -Encoding utf8
$traceArgs=@('-Xms2048m','-Xmx2048m','-XX:+UseG1GC',
    "-javaagent:$traceBuild/artifacts/timeline-agent.jar=$traceBuild",
    "-Dan.production.output=$traceRun","-Dan.trace.suite=$Suite","-Dan.trace.scene=$Scene",
    "-Dan.production.performanceVanilla=$($Native.ToString().ToLowerInvariant())",
    "-Dan.trace.disabled=$($NoTrace.ToString().ToLowerInvariant())",
    "-Dan.trace.omitObservations=$($OmitObservations.ToString().ToLowerInvariant())",
    "-Dan.trace.tailChecks=$($TailChecks.ToString().ToLowerInvariant())",
    "-Dan.trace.tailAction=$TailAction",
    "-Dan.trace.replay=$($Replay.ToString().ToLowerInvariant())",
    "-Dan.trace.navigationLogLevel=$NavigationLogLevel",
    "-Dan.trace.cityWorld=$($CityWorld.ToString().ToLowerInvariant())",
    '-Dan.production.performanceSpeedAmplifier=1',
    "-Djdk.net.unixdomain.tmpdir=$traceRun/absent-socket",
    '-Xlog:gc=info,safepoint=info:file=runtime.log:timenanos,level,tags')
if($Jfr){$traceArgs+="-XX:StartFlightRecording=settings=profile,dumponexit=true,maxsize=128m,filename=$traceRun/server.jfr"}
if($NavigationLogLevel -eq 'TRACE'){
    Copy-Item -LiteralPath "$PSScriptRoot/log4j2-trace.xml" -Destination "$traceRun/launch-log4j2.xml"
    $traceLogUri=([uri](Join-Path $traceRun 'launch-log4j2.xml')).AbsoluteUri
    $traceArgs+="-Dlog4j.configurationFile=$traceLogUri"
}
[IO.File]::WriteAllLines("$traceRun/jvm.args",@($traceArgs|ForEach-Object{'"'+$_.Replace('\','/')+'"'}),[Text.UTF8Encoding]::new($false))
$traceArtifacts=@(Get-ChildItem "$traceBuild/artifacts" -File|ForEach-Object{
    @{name=$_.Name;sha256=(Get-FileHash $_.FullName).Hash.ToLowerInvariant()}
})
@{suite=$Suite;scene=$Scene;native=[bool]$Native;deepTrace=(-not $NoTrace);replay=[bool]$Replay;jfr=[bool]$Jfr;args=$traceArgs;
    navigationLogLevel=$NavigationLogLevel;cityWorld=[bool]$CityWorld;worldInputs=$traceWorldInputs;
    omitObservations=[bool]$OmitObservations;
    tailChecks=[bool]$TailChecks;
    tailAction=$TailAction;
    logConfigurationSha256=if($NavigationLogLevel -eq 'TRACE'){(Get-FileHash -LiteralPath "$traceRun/launch-log4j2.xml").Hash.ToLowerInvariant()}else{$null};
    artifacts=$traceArtifacts;build=$traceBuild;javaSha256=(Get-FileHash "$traceJava/java.exe").Hash.ToLowerInvariant();
    templateManifestSha256=(Get-FileHash "$traceRepo/.tmp/current-performance-reference-20261016/template-manifest.json").Hash.ToLowerInvariant();
    serverPropertiesSha256=(Get-FileHash "$traceRun/server.properties").Hash.ToLowerInvariant();
    buildIdentitySha256=(Get-FileHash "$traceBuild/build-identity.json").Hash.ToLowerInvariant();
    startedUtc=[DateTime]::UtcNow.ToString('o')}|ConvertTo-Json -Depth 6|Set-Content "$traceRun/launch.json" -Encoding utf8
$env:MOD_CLASSES=$null
$env:MCP_MAPPINGS=$null
$env:JAVA_TOOL_OPTIONS=$null
$env:GRADLE_OPTS=$null
$traceProcess=$null
$traceTimedOut=$false
$traceMonitor=[Collections.Generic.List[object]]::new()
try{
    $traceStarted=[DateTime]::UtcNow
    $traceProcess=Start-Process -FilePath "$traceJava/java.exe" -WindowStyle Hidden -PassThru `
        -ArgumentList "@`"$traceRun/jvm.args`" @libraries/net/minecraftforge/forge/1.20.1-47.3.0/win_args.txt --nogui" `
        -WorkingDirectory $traceRun -RedirectStandardOutput "$traceRun/stdout.txt" -RedirectStandardError "$traceRun/stderr.txt"
    Write-Output "TIMELINE_STARTED $Suite pid=$($traceProcess.Id)"
    while(-not $traceProcess.WaitForExit(1000)){
        $traceOthers=@(Get-Process -Name java,javaw -ErrorAction SilentlyContinue|
            Where-Object {$_.Id -ne $traceProcess.Id}|Select-Object Id,ProcessName)
        $traceMonitor.Add(@{utc=[DateTime]::UtcNow.ToString('o');otherJava=$traceOthers})
        if(([DateTime]::UtcNow-$traceStarted).TotalSeconds -gt 1500 -or (Get-PSDrive -Name D).Free -lt 2GB){
            $traceTimedOut=$true;Stop-Process -Id $traceProcess.Id;$traceProcess.WaitForExit();break
        }
    }
    $traceProcess.Refresh()
    @{pid=$traceProcess.Id;exitCode=$traceProcess.ExitCode;timedOut=$traceTimedOut;
        startedUtc=$traceStarted.ToString('o');endedUtc=[DateTime]::UtcNow.ToString('o');
        finalServerPropertiesSha256=(Get-FileHash "$traceRun/server.properties").Hash.ToLowerInvariant();
        concurrentJava=@($traceMonitor|Where-Object {$_.otherJava.Count -gt 0});
        samples=$traceMonitor.ToArray()}|ConvertTo-Json -Depth 7|Set-Content "$traceRun/exit.json" -Encoding utf8
    if($traceTimedOut -or $traceProcess.ExitCode){throw 'Diagnostic JVM failed; retained'}
    $traceResult=Get-Content -Raw "$traceRun/timeline.json"|ConvertFrom-Json
    $traceShutdown=Get-Content -Raw "$traceRun/shutdown.json"|ConvertFrom-Json
    if(-not $traceResult.complete -or $traceResult.failure -or -not $traceShutdown.passed){throw 'Incomplete diagnostic; retained'}
    foreach($traceTrial in $traceResult.trials){
        if($traceTrial.submittedNanos-$traceTrial.sceneReadyNanos -lt 30000000000L -or
            $traceTrial.submittedNanos-$traceTrial.serverStartedNanos -lt 30000000000L){throw '30s gate failed'}
    }
    if(@($traceMonitor|Where-Object {$_.otherJava.Count -gt 0}).Count){throw 'External Java interference; retained'}
    if($TailChecks -or $TailAction -ne 'none'){
        $traceTail=Get-Content -Raw "$traceRun/tail-checks.json"|ConvertFrom-Json
        if(-not $traceTail.passed){throw 'Tail boundary checks failed; retained'}
    }
    if($NavigationLogLevel -eq 'TRACE' -and
            (-not (Test-Path -LiteralPath "$traceRun/navigation.log") -or
             (Get-Item -LiteralPath "$traceRun/navigation.log").Length -eq 0)){
        throw 'TRACE sink missing; retained'
    }
    Write-Output "TIMELINE_FINISHED $Suite trials=$($traceResult.trials.Count)"
}finally{
    if($null -ne $traceProcess -and -not $traceProcess.HasExited){Stop-Process -Id $traceProcess.Id;$traceProcess.WaitForExit()}
}
