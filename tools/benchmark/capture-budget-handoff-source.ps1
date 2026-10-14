param(
    [string]$EvidenceRoot = '.tmp/navigation-budget-handoff-20261013-grace'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskWorkspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskOutput = Join-Path $taskWorkspace $EvidenceRoot
$taskJar = Join-Path $env:USERPROFILE '.gradle/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/1.20.1-47.3.0_mapped_official_1.20.1/forge-1.20.1-47.3.0_mapped_official_1.20.1-sources.jar'
$taskJarHash = (Get-FileHash -LiteralPath $taskJar -Algorithm SHA256).Hash
if ($taskJarHash -ne '5905C044CBF9D4CD0208F8878BFD7B586782C44366C277D6FBD70E4B075C0EB2') {
    throw 'Pinned Minecraft/Forge sources changed'
}
$taskSpecs = @(
    @{path='net/minecraft/world/entity/ai/navigation/PathNavigation.java'; terms=@('protected void followThePath()', 'maxDistanceToWaypoint =', 'boolean flag =', 'this.path.advance();', 'protected void doStuckDetection')},
    @{path='net/minecraft/world/level/pathfinder/WalkNodeEvaluator.java'; terms=@('public Node getStart()', 'BlockPos blockpos1 = this.mob.blockPosition();', 'protected Node getStartNode')},
    @{path='net/minecraft/world/level/pathfinder/Path.java'; terms=@('public Vec3 getEntityPosAtNode', 'public boolean isDone()', 'public void advance()')},
    @{path='net/minecraft/world/level/pathfinder/PathFinder.java'; terms=@('public Path findPath', 'getStart()', 'maxVisitedNodes', 'getNeighbors')},
    @{path='net/minecraft/world/entity/ai/control/MoveControl.java'; terms=@('public void tick()', 'this.operation = MoveControl.Operation.WAIT;', 'this.mob.setZza(0.0F);')}
)
$taskArchive = [IO.Compression.ZipFile]::OpenRead($taskJar)
try {
    $taskEntries = foreach ($taskSpec in $taskSpecs) {
        $taskReader = [IO.StreamReader]::new($taskArchive.GetEntry($taskSpec.path).Open())
        try { $taskSource = $taskReader.ReadToEnd() } finally { $taskReader.Dispose() }
        $taskDestination = Join-Path $taskOutput ('upstream/' + $taskSpec.path)
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($taskDestination)) | Out-Null
        if (Test-Path -LiteralPath $taskDestination) { throw 'Preserve prior source evidence' }
        [IO.File]::WriteAllText($taskDestination, $taskSource, [Text.UTF8Encoding]::new($false))
        $taskLines = $taskSource -split '\r?\n'
        $taskMatches = for ($taskIndex = 0; $taskIndex -lt $taskLines.Count; $taskIndex++) {
            foreach ($taskTerm in $taskSpec.terms) {
                if ($taskLines[$taskIndex].Contains($taskTerm)) {
                    [ordered]@{line=$taskIndex+1; term=$taskTerm; text=$taskLines[$taskIndex].Trim()}
                }
            }
        }
        [ordered]@{entry=$taskSpec.path; output=$taskDestination;
            sha256=(Get-FileHash -LiteralPath $taskDestination -Algorithm SHA256).Hash; matches=@($taskMatches)}
    }
} finally { $taskArchive.Dispose() }
[ordered]@{
    schema='PINNED_FORGE_47_3_0_HANDOFF_SOURCE'
    jar=$taskJar
    jarSha256=$taskJarHash
    scriptSha256=(Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash
    entries=@($taskEntries)
} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $taskOutput 'upstream-identities.json') -Encoding utf8
