[CmdletBinding()]
param(
    [ValidateSet('msi', 'app-image')]
    [string]$Type = 'msi',
    [string]$OutputDirectory,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$target = Join-Path $root 'target'
$inputDir = Join-Path $target ('package-input-' + [guid]::NewGuid().ToString('N'))
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $target 'dist' }
$dist = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputDirectory)
[xml]$pom = Get-Content -Raw -LiteralPath (Join-Path $root 'pom.xml')
$mavenVersion = [string]$pom.project.version
$version = [version]($mavenVersion.Split('-')[0])
$appVersion = '{0}.{1}.{2}' -f $version.Major, $version.Minor, [Math]::Max(0, $version.Build)
$mainJar = '{0}-{1}.jar' -f $pom.project.artifactId, $mavenVersion
$result = if ($Type -eq 'msi') { Join-Path $dist "Daymark-$appVersion.msi" } else { Join-Path $dist 'Daymark' }
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 21 installation.' }
$jpackage = Join-Path $env:JAVA_HOME 'bin\jpackage.exe'
if (-not (Test-Path -LiteralPath $jpackage)) { throw "jpackage not found: $jpackage" }
if ($Type -eq 'msi' -and (-not (Get-Command candle.exe -ErrorAction SilentlyContinue) -or -not (Get-Command light.exe -ErrorAction SilentlyContinue))) {
    throw 'MSI packaging requires WiX Toolset 3 (candle.exe and light.exe) on PATH.'
}
if (Test-Path -LiteralPath $result) { throw "Move the existing package before rebuilding: $result" }
New-Item -ItemType Directory -Force -Path $inputDir, $dist | Out-Null
Push-Location $root
try {
    $mavenArgs = @('--batch-mode', '--no-transfer-progress')
    if ($Offline) { $mavenArgs += '-o' }
    $mavenArgs += @('compile', 'org.apache.maven.plugins:maven-jar-plugin:3.4.1:jar',
        'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies',
        '-DincludeScope=runtime', "-DoutputDirectory=$inputDir")
    & mvn @mavenArgs
    if ($LASTEXITCODE -ne 0) { throw "Maven failed with exit code $LASTEXITCODE" }
    Copy-Item -LiteralPath (Join-Path $target $mainJar) -Destination $inputDir
    & $jpackage --type $Type --name Daymark --app-version $appVersion --input $inputDir --dest $dist `
        --main-jar $mainJar --main-class com.daymark.app.DaymarkLauncher --vendor Daymark
    if ($LASTEXITCODE -ne 0) { throw "jpackage failed with exit code $LASTEXITCODE" }
    if (-not (Test-Path -LiteralPath $result)) { throw "Expected package missing: $result" }
    Write-Host "Package created: $result"
} finally { Pop-Location }
