$ErrorActionPreference='Stop'
$taskWorkspace=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$taskEvidence=Join-Path $taskWorkspace '.tmp/shared-topology-production-20261015'
$taskTarget=Join-Path $taskEvidence 'current-release-v1'
if(Test-Path -LiteralPath $taskTarget){throw 'Preserve release equivalence evidence'}
$taskCandidate=Join-Path $taskEvidence 'candidate-v5/release/production.jar'
$taskCurrent=Join-Path $taskWorkspace 'build/libs/accelerated_navigation-0.1.0-all.jar'
$taskProbe=Join-Path $taskWorkspace 'build/reobfProductionProbeJar/output.jar'
$taskLeft=[IO.Compression.ZipFile]::OpenRead($taskCandidate)
$taskRight=[IO.Compression.ZipFile]::OpenRead($taskCurrent)
$taskRows=@()
$taskHash=[Security.Cryptography.SHA256]::Create()
function Get-EntryHash($taskEntry) {
    $taskStream=$taskEntry.Open()
    try{return [Convert]::ToHexString($taskHash.ComputeHash($taskStream))}
    finally{$taskStream.Dispose()}
}
function Get-Manifest($taskArchive) {
    $taskReader=[IO.StreamReader]::new($taskArchive.GetEntry('META-INF/MANIFEST.MF').Open())
    try{return $taskReader.ReadToEnd()}finally{$taskReader.Dispose()}
}
try {
    if($taskLeft.Entries.Count -ne $taskRight.Entries.Count){throw 'Entry counts changed'}
    foreach($taskEntry in $taskLeft.Entries) {
        $taskOther=$taskRight.GetEntry($taskEntry.FullName)
        if($null -eq $taskOther){throw 'Entry names changed'}
        $taskSHA=Get-EntryHash $taskEntry
        $taskOtherSHA=Get-EntryHash $taskOther
        if($taskSHA -ne $taskOtherSHA -and $taskEntry.FullName -ne 'META-INF/MANIFEST.MF'){
            throw ('Payload changed: '+$taskEntry.FullName)
        }
        $taskRows+=@{entry=$taskEntry.FullName;candidateSha256=$taskSHA;currentSha256=$taskOtherSHA}
    }
    $taskOldManifest=Get-Manifest $taskLeft
    $taskNewManifest=Get-Manifest $taskRight
    $taskTimestamp='(?m)^Implementation-Timestamp:.*\r?\n'
    if([Regex]::Replace($taskOldManifest,$taskTimestamp,'') -cne [Regex]::Replace($taskNewManifest,$taskTimestamp,'')){
        throw 'Manifest changed beyond build timestamp'
    }
}finally{$taskLeft.Dispose();$taskRight.Dispose();$taskHash.Dispose()}
New-Item -Path $taskTarget -ItemType Directory | Out-Null
Copy-Item -LiteralPath $taskCurrent -Destination (Join-Path $taskTarget 'production.jar')
Copy-Item -LiteralPath $taskProbe -Destination (Join-Path $taskTarget 'probe.jar')
$taskResult=@{schema='SHARED_CURRENT_RELEASE_EQUIVALENCE_V1';passed=$true;entries=$taskRows;
    candidateSha256=(Get-FileHash -LiteralPath $taskCandidate).Hash;
    productionSha256=(Get-FileHash -LiteralPath (Join-Path $taskTarget 'production.jar')).Hash;
    probeSha256=(Get-FileHash -LiteralPath (Join-Path $taskTarget 'probe.jar')).Hash;
    oldManifest=$taskOldManifest;currentManifest=$taskNewManifest;
    scope='all jar entries equal by content except Implementation-Timestamp; lifecycle-v4 is earlier byte identity, not relabelled as current bytes'}
$taskResult | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $taskTarget 'equivalence.json') -Encoding utf8
$taskResult | Select-Object schema,passed,productionSha256,probeSha256 | ConvertTo-Json
