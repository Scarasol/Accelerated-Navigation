param([Parameter(Mandatory)][string]$Output,[switch]$CurrentSource,[string]$SourceRoot='')
$ErrorActionPreference='Stop'
if($SourceRoot -and -not $CurrentSource){throw 'SourceRoot requires CurrentSource'}
$traceRepo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$traceOut=[IO.Path]::GetFullPath($Output,$traceRepo)
if(Test-Path -LiteralPath $traceOut){throw 'Build output must be new'}
$traceJava='C:\Program Files\Java\jdk-17.0.18+8\bin'
$traceCp=@(Get-Content -Raw (Join-Path $traceRepo '.tmp/intermediate-reuse-production-20261016/classpath.json')|ConvertFrom-Json)
$traceAsm=@($traceCp|Where-Object {$_ -match '[\\/]org\.ow2\.asm[\\/](asm|asm-tree)[\\/]'})
if($traceAsm.Count -ne 2){throw 'Expected qualified ASM core and tree'}
New-Item -ItemType Directory -Force -Path "$traceOut/artifacts","$traceOut/classes/helper","$traceOut/classes/agent","$traceOut/classes/check","$traceOut/selfcheck","$traceOut/selfcheck-capacity"|Out-Null
& "$traceJava/javac.exe" -encoding UTF-8 -proc:none -d "$traceOut/classes/helper" "$PSScriptRoot/helper/an/trace/Trace.java"
if($LASTEXITCODE){throw 'Helper compilation failed'}
& "$traceJava/jar.exe" --create --file "$traceOut/artifacts/trace-helper.jar" -C "$traceOut/classes/helper" .
& "$traceJava/javac.exe" -encoding UTF-8 -proc:none -cp ((@("$traceOut/classes/helper")+$traceAsm)-join ';') -d "$traceOut/classes/agent" "$PSScriptRoot/agent/an/trace/Agent.java"
if($LASTEXITCODE){throw 'Agent compilation failed'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach($traceDep in $traceAsm){
    $traceZip=[IO.Compression.ZipFile]::OpenRead($traceDep)
    try{
        foreach($traceEntry in $traceZip.Entries){
            if(-not $traceEntry.FullName.StartsWith('org/objectweb/asm/') -or -not $traceEntry.FullName.EndsWith('.class')){continue}
            $traceDestination=Join-Path "$traceOut/classes/agent" $traceEntry.FullName
            [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($traceDestination))|Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($traceEntry,$traceDestination,$false)
        }
    }finally{$traceZip.Dispose()}
}
& "$traceJava/jar.exe" --create --file "$traceOut/artifacts/timeline-agent.jar" --manifest "$PSScriptRoot/agent.mf" -C "$traceOut/classes/agent" .
& "$traceJava/javac.exe" -encoding UTF-8 -proc:none -cp "$traceOut/classes/helper" -d "$traceOut/classes/check" "$PSScriptRoot/check/an/trace/SelfCheck.java" "$PSScriptRoot/check/an/trace/JfrExtract.java"
if($LASTEXITCODE){throw 'Selfcheck compilation failed'}
& "$traceJava/java.exe" "-javaagent:$traceOut/artifacts/timeline-agent.jar=$traceOut" "-Dan.production.output=$traceOut/selfcheck" -cp "$traceOut/classes/check" an.trace.SelfCheck
if($LASTEXITCODE){throw 'Selfcheck failed'}
& "$traceJava/java.exe" "-javaagent:$traceOut/artifacts/timeline-agent.jar=$traceOut" "-Dan.trace.capacity=2" "-Dan.production.output=$traceOut/selfcheck-capacity" -cp "$traceOut/classes/check" an.trace.SelfCheck
if($LASTEXITCODE){throw 'Capacity fail-open selfcheck failed'}
$traceIdentity=Get-Content -Raw "$traceRepo/.tmp/current-performance-reference-20261016/frozen/identity.json"|ConvertFrom-Json
$traceMain="$traceRepo/.tmp/current-performance-reference-20261016/frozen/main"
if($CurrentSource){
    $traceInput=if($SourceRoot){(Resolve-Path -LiteralPath $SourceRoot).Path}else{"$traceRepo/src/main"}
    Copy-Item -LiteralPath $traceInput -Destination "$traceOut/production-source" -Recurse
    $traceMain="$traceOut/production-source"
}else{
    foreach($traceSource in $traceIdentity.sourceFiles|Where-Object {$_.path.StartsWith('src/main/')}){
        if((Get-FileHash -LiteralPath (Join-Path $traceRepo $traceSource.path)).Hash.ToLowerInvariant() -ne $traceSource.candidateSha256){throw "Production source drift $($traceSource.path)"}
    }
    Copy-Item -LiteralPath "$traceRepo/.tmp/current-performance-reference-20261016/frozen/accelerated_navigation.jar" -Destination "$traceOut/artifacts/production.jar"
    if((Get-FileHash "$traceOut/artifacts/production.jar").Hash.ToLowerInvariant() -ne $traceIdentity.productionJarSha256){throw 'Production identity mismatch'}
}
$traceSocket=("$traceOut/absent-gradle-socket").Replace('\','/')
$env:JAVA_HOME=Split-Path $traceJava -Parent
$env:GRADLE_OPTS="-Djdk.net.unixdomain.tmpdir=$traceSocket -Djava.net.preferIPv4Stack=true"
$env:JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=$traceSocket"
$traceTasks=@('reobfTimelineProbeJar')
if($CurrentSource){$traceTasks+='jarJar'}
& "$traceRepo/gradlew.bat" --offline --no-daemon --max-workers=2 --console plain `
    "-Dorg.gradle.jvmargs=-Xmx2G -Djdk.net.unixdomain.tmpdir=$traceSocket -Djava.net.preferIPv4Stack=true" `
    "-Dan.trace.build=$traceOut" "-Dan.trace.main=$traceMain" -I "$PSScriptRoot/isolated.gradle" @traceTasks
if($LASTEXITCODE){throw 'Probe build failed'}
$traceFiles=@(Get-ChildItem -LiteralPath $PSScriptRoot -File -Recurse|ForEach-Object{
    @{path=$_.FullName;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}
})
Copy-Item -LiteralPath $PSScriptRoot -Destination "$traceOut/source" -Recurse
Copy-Item -LiteralPath "$traceRepo/src/test/java/an/production/ProductionBudgetChecks.java" -Destination "$traceOut/source/ProductionBudgetChecks.java"
@{productionJarSha256=(Get-FileHash "$traceOut/artifacts/production.jar").Hash.ToLowerInvariant();
    currentSource=[bool]$CurrentSource;productionSource=$traceMain;
    budgetChecksSha256=(Get-FileHash "$traceOut/source/ProductionBudgetChecks.java").Hash.ToLowerInvariant();
    productionSources=@(Get-ChildItem -LiteralPath $traceMain -File -Recurse|ForEach-Object{
        @{path=[IO.Path]::GetRelativePath($traceMain,$_.FullName);sha256=(Get-FileHash $_.FullName).Hash.ToLowerInvariant()}
    });tools=$traceFiles;asm=$traceAsm;
    referenceSources=@(Get-ChildItem "$traceRepo/.tmp/current-performance-reference-20261016/probe" -File -Recurse|ForEach-Object{
        @{path=$_.FullName;sha256=(Get-FileHash $_.FullName).Hash.ToLowerInvariant()}
    });createdUtc=[DateTime]::UtcNow.ToString('o')}|
    ConvertTo-Json -Depth 5|Set-Content -LiteralPath "$traceOut/build-identity.json" -Encoding utf8
