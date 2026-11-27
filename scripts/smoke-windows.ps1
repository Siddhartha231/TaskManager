[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$Launcher)
$ErrorActionPreference = 'Stop'
$launcherPath = (Resolve-Path -LiteralPath $Launcher).Path
$lib = Join-Path (Split-Path -Parent $launcherPath) 'app'
if (-not (Get-ChildItem -Path (Join-Path $lib 'sqlite-jdbc-*.jar') -ErrorAction SilentlyContinue)) { throw "SQLite dependency missing from $lib" }
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 21 installation.' }
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path -LiteralPath $java)) { throw "Java not found: $java" }
$fixture = Join-Path $PSScriptRoot 'SmokeDatabase.java'
$tempDir = (Resolve-Path -LiteralPath ([IO.Path]::GetTempPath())).ProviderPath
$testRoot = Join-Path $tempDir ('daymark-smoke-' + [guid]::NewGuid().ToString('N'))
$originalLocalAppData = $env:LOCALAPPDATA
New-Item -ItemType Directory -Path $testRoot | Out-Null
function Invoke-Fixture([string]$Action, [string]$Path) {
    & $java --class-path "$lib\*" $fixture $Action $Path
    if ($LASTEXITCODE -ne 0) { throw "Fixture operation failed: $Action" }
}
function Invoke-Launch([string]$WorkDir, [string]$DataDir) {
    $env:LOCALAPPDATA = $DataDir
    $process = Start-Process -FilePath $launcherPath -WorkingDirectory $WorkDir -PassThru
    try {
        $deadline = (Get-Date).AddSeconds(45)
        do {
            Start-Sleep -Milliseconds 500
            $process.Refresh()
            if ($process.HasExited) { throw "Daymark exited before opening a window (exit $($process.ExitCode))." }
            if ($process.MainWindowHandle -ne 0 -and $process.MainWindowTitle -eq 'Daymark') { break }
        } while ((Get-Date) -lt $deadline)
        if ($process.MainWindowHandle -eq 0 -or $process.MainWindowTitle -ne 'Daymark') { throw 'Daymark window did not open within 45 seconds.' }
        if (-not $process.CloseMainWindow()) { throw 'Could not close the Daymark window.' }
        if (-not $process.WaitForExit(15000)) { throw 'Daymark did not close within 15 seconds.' }
        if ($process.ExitCode -ne 0) { throw "Daymark exited with code $($process.ExitCode)." }
    } finally {
        $process.Refresh()
        if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force; $process.WaitForExit() }
    }
}
try {
    $freshWork = Join-Path $testRoot 'fresh-work'
    $freshData = Join-Path $testRoot 'fresh-data'
    $legacyWork = Join-Path $testRoot 'legacy-work'
    $legacyData = Join-Path $testRoot 'legacy-data'
    New-Item -ItemType Directory -Path $freshWork, $freshData, $legacyWork, $legacyData | Out-Null
    $freshDb = Join-Path $freshData 'Daymark\daymark.db'
    Invoke-Launch $freshWork $freshData
    if (-not (Test-Path -LiteralPath $freshDb)) { throw 'Fresh profile database was not created.' }
    Invoke-Fixture 'insert' $freshDb
    Invoke-Launch $freshWork $freshData
    Invoke-Fixture 'check-restart' $freshDb
    $legacyDb = Join-Path $legacyWork 'daymark.db'
    $migratedDb = Join-Path $legacyData 'Daymark\daymark.db'
    Invoke-Fixture 'legacy' $legacyDb
    $legacyHash = (Get-FileHash -LiteralPath $legacyDb -Algorithm SHA256).Hash
    Invoke-Launch $legacyWork $legacyData
    if (-not (Test-Path -LiteralPath $migratedDb)) { throw 'Legacy database was not migrated.' }
    if ((Get-FileHash -LiteralPath $legacyDb -Algorithm SHA256).Hash -ne $legacyHash) { throw 'Legacy database changed during migration.' }
    Invoke-Fixture 'check-legacy' $migratedDb
    Invoke-Launch $legacyWork $legacyData
    Invoke-Fixture 'check-legacy' $migratedDb
    Write-Host 'Packaged launcher, fresh profile, legacy migration, and restart smoke checks passed.'
} finally {
    $env:LOCALAPPDATA = $originalLocalAppData
    $resolvedRoot = Resolve-Path -LiteralPath $testRoot -ErrorAction SilentlyContinue
    if ($resolvedRoot -and (Split-Path -Parent $resolvedRoot.ProviderPath) -eq $tempDir.TrimEnd('\')) {
        Remove-Item -LiteralPath $resolvedRoot.ProviderPath -Recurse -Force
    }
}
