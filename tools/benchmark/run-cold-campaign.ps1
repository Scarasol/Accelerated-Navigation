$ErrorActionPreference='Stop'
$taskWorkspace=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$taskRoot=Join-Path $taskWorkspace '.tmp/shared-topology-production-20261015'
$taskProbe='.tmp/shared-topology-production-20261015/cold-instrument-v3/probe.jar'
$taskBaseline='.tmp/shared-topology-production-20261015/baseline-da55e12/accelerated_navigation-0.1.0-all.jar'
$taskShared='.tmp/shared-topology-production-20261015/candidate-v5/release/production.jar'
$taskRows=@(
    @('diagnostic','baseline','super',0),@('diagnostic','shared','same',0),
    @('diagnostic','baseline','base',0),@('diagnostic','shared','super',0),
    @('diagnostic','baseline','same',0),@('diagnostic','shared','base',0),
    @('observation','shared','super',1),@('observation','baseline','base',1),
    @('observation','shared','same',1),@('observation','baseline','super',1),
    @('observation','shared','base',1),@('observation','baseline','same',1),
    @('observation','baseline','same',2),@('observation','shared','base',2),
    @('observation','baseline','super',2),@('observation','shared','same',2),
    @('observation','baseline','base',2),@('observation','shared','super',2)
)
$taskRuns=@()
foreach($taskRow in $taskRows) {
    $taskName='cold-'+$taskRow[0]+'-v3-'+$taskRow[1]+'-'+$taskRow[2]
    if($taskRow[3]){$taskName+='-r'+$taskRow[3]}
    $taskRuns+=$taskName
    $taskPath=Join-Path $taskRoot $taskName
    if(Test-Path -LiteralPath $taskPath) {
        if($taskName -ne 'cold-diagnostic-v3-baseline-super'){throw 'Preserve previous campaign attempt'}
        $taskPilot=Get-Content -LiteralPath (Join-Path $taskPath 'cold-topology.json') -Raw|ConvertFrom-Json
        $taskPilotExit=Get-Content -LiteralPath (Join-Path $taskPath 'exit.json') -Raw|ConvertFrom-Json
        if(-not $taskPilot.passed -or $taskPilotExit.timedOut -or $taskPilotExit.exitCode -ne 0 -or
                (Get-Process -Id $taskPilotExit.pid -ErrorAction SilentlyContinue)){throw 'Pilot is not eligible'}
        continue
    }
    $taskJar=if($taskRow[1] -eq 'baseline'){$taskBaseline}else{$taskShared}
    Write-Output ('COLD_SERIAL_START '+$taskName)
    & (Join-Path $PSScriptRoot 'run-production-checks.ps1') -Run $taskName -Probe cold `
        -ColdRoute $taskRow[2] -ColdStages:($taskRow[0] -eq 'diagnostic') `
        -ProductionJar $taskJar -ProbeJar $taskProbe -EvidenceRoot .tmp/shared-topology-production-20261015
}
& node (Join-Path $PSScriptRoot 'analyze-cold-topology.mjs') matrix-v3 @taskRuns
if($LASTEXITCODE -ne 0){throw 'Independent cold reader rejected qualification'}
