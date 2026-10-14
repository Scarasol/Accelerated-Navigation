param(
    [string]$Output = '.tmp/navigation-dynamic-budget-20261013/tick-source.json'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskWorkspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskGradleCache = Join-Path $env:USERPROFILE '.gradle/caches/forge_gradle'
$taskSources = @(
    @{
        Jar = 'minecraft_user_repo/net/minecraftforge/forge/1.20.1-47.3.0_mapped_official_1.20.1/forge-1.20.1-47.3.0_mapped_official_1.20.1-sources.jar'
        Entries = @(
            @{ Path = 'net/minecraft/server/MinecraftServer.java'; Terms = @('this.tickServer(this::haveTime)', 'this.waitUntilNextTick();', 'public void tickServer(', 'onPreServerTick', 'this.tickTimes[this.tickCount % 100]', 'onPostServerTick', 'private boolean haveTime()') },
            @{ Path = 'net/minecraft/client/server/IntegratedServer.java'; Terms = @('public void tickServer(', 'super.tickServer(', 'Changing view distance', 'Changing simulation distance') }
        )
    },
    @{
        Jar = 'maven_downloader/net/minecraftforge/forge/1.20.1-47.3.0/forge-1.20.1-47.3.0-sources.jar'
        Entries = @(
            @{ Path = 'net/minecraftforge/event/TickEvent.java'; Terms = @('class ServerTickEvent', 'public boolean haveTime()', 'return this.haveTime.getAsBoolean()') },
            @{ Path = 'net/minecraftforge/event/ForgeEventFactory.java'; Terms = @('onPreServerTick(', 'onPostServerTick(', 'TickEvent.Phase.START', 'TickEvent.Phase.END') }
        )
    }
)
$taskEvidence = foreach ($taskSource in $taskSources) {
    $taskJar = Join-Path $taskGradleCache $taskSource.Jar
    $taskArchive = [IO.Compression.ZipFile]::OpenRead($taskJar)
    try {
        $taskEntries = foreach ($taskEntrySpec in $taskSource.Entries) {
            $taskReader = [IO.StreamReader]::new($taskArchive.GetEntry($taskEntrySpec.Path).Open())
            try { $taskLines = $taskReader.ReadToEnd() -split '\r?\n' }
            finally { $taskReader.Dispose() }
            $taskMatches = for ($taskIndex = 0; $taskIndex -lt $taskLines.Count; $taskIndex++) {
                foreach ($taskTerm in $taskEntrySpec.Terms) {
                    if ($taskLines[$taskIndex].Contains($taskTerm)) {
                        [ordered]@{ line = $taskIndex + 1; term = $taskTerm; text = $taskLines[$taskIndex].Trim() }
                    }
                }
            }
            [ordered]@{ path = $taskEntrySpec.Path; matches = @($taskMatches) }
        }
        [ordered]@{
            jar = $taskJar
            sha256 = (Get-FileHash -LiteralPath $taskJar -Algorithm SHA256).Hash
            entries = @($taskEntries)
        }
    } finally { $taskArchive.Dispose() }
}
$taskOutput = [IO.Path]::GetFullPath((Join-Path $taskWorkspace $Output))
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($taskOutput)) | Out-Null
$taskJson = [ordered]@{
    schema = 'PINNED_FORGE_47_3_0_TICK_SOURCE'
    version = 'Minecraft 1.20.1 / Forge 47.3.0'
    sourceScriptSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash
    archives = @($taskEvidence)
} | ConvertTo-Json -Depth 9
[IO.File]::WriteAllText($taskOutput, $taskJson + "`n", [Text.UTF8Encoding]::new($false))
Write-Output $taskOutput
