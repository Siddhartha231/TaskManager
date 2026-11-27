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
$passed = $false
New-Item -ItemType Directory -Path $testRoot | Out-Null
function Invoke-Fixture([string]$Action, [string]$Path) {
    & $java --class-path "$lib\*" $fixture $Action $Path 2>&1 | Write-Host
    if ($LASTEXITCODE -ne 0) { throw "Fixture operation failed: $Action" }
}
function Invoke-Launch([string]$WorkDir, [string]$DataDir) {
    $env:LOCALAPPDATA = $DataDir
    $launchId = 'startup-' + [guid]::NewGuid().ToString('N')
    $marker = Join-Path $testRoot "$launchId.ok"
    $stdout = Join-Path $testRoot "$launchId.out.log"
    $stderr = Join-Path $testRoot "$launchId.err.log"
    $argument = '--startup-check="' + $marker + '"'
    $process = Start-Process -FilePath $launcherPath -WorkingDirectory $WorkDir -ArgumentList $argument `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    try {
        if (-not $process.WaitForExit(45000)) { throw 'Daymark did not finish its startup check within 45 seconds.' }
        if ($process.ExitCode -ne 0) { throw "Daymark exited with code $($process.ExitCode)." }
        if (-not (Test-Path -LiteralPath $marker -PathType Leaf)) { throw 'Daymark did not confirm startup and shutdown.' }
    } catch {
        Get-Content -LiteralPath $stdout, $stderr -ErrorAction SilentlyContinue | Write-Host
        throw
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
    Write-Host 'Opening the packaged app with a fresh profile.'
    Invoke-Launch $freshWork $freshData
    if (-not (Test-Path -LiteralPath $freshDb)) { throw 'Fresh profile database was not created.' }
    Invoke-Fixture 'insert' $freshDb
    Invoke-Launch $freshWork $freshData
    Invoke-Fixture 'check-restart' $freshDb
    $legacyDb = Join-Path $legacyWork 'daymark.db'
    $migratedDb = Join-Path $legacyData 'Daymark\daymark.db'
    Write-Host 'Opening the packaged app with a legacy database fixture.'
    Invoke-Fixture 'legacy' $legacyDb
    $legacyHash = (Get-FileHash -LiteralPath $legacyDb -Algorithm SHA256).Hash
    Invoke-Launch $legacyWork $legacyData
    if (-not (Test-Path -LiteralPath $migratedDb)) { throw 'Legacy database was not migrated.' }
    if ((Get-FileHash -LiteralPath $legacyDb -Algorithm SHA256).Hash -ne $legacyHash) { throw 'Legacy database changed during migration.' }
    Invoke-Fixture 'check-legacy' $migratedDb
    Invoke-Launch $legacyWork $legacyData
    Invoke-Fixture 'check-legacy' $migratedDb
    $passed = $true
    Write-Host 'Packaged launcher, fresh profile, legacy migration, and restart smoke checks passed.'
} finally {
    $env:LOCALAPPDATA = $originalLocalAppData
    $resolvedRoot = Resolve-Path -LiteralPath $testRoot -ErrorAction SilentlyContinue
    if ($passed -and $resolvedRoot -and (Split-Path -Parent $resolvedRoot.ProviderPath) -eq $tempDir.TrimEnd('\')) {
        try {
            Remove-Item -LiteralPath $resolvedRoot.ProviderPath -Recurse -Force -ErrorAction Stop
        } catch {
            Write-Warning "Could not clean up the smoke-test fixtures at $testRoot."
        }
    } elseif (-not $passed) {
        Write-Host "Smoke-test fixtures are available at $testRoot."
    }
}
