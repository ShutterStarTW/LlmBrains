param([switch]$ShowPaths)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..\..")).Path
$gradleWrapper = Join-Path $repositoryRoot "gradlew.bat"
$arguments = @("runProjectDiscovery", "--console=plain")
if ($ShowPaths) {
    $arguments += "-PshowPaths=true"
}

Push-Location -LiteralPath $repositoryRoot
try {
    & $gradleWrapper @arguments
} finally {
    Pop-Location
}
if ($LASTEXITCODE -ne 0) {
    throw "Project discovery smoke CLI failed with exit code $LASTEXITCODE."
}
