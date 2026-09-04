$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..\..")).Path
$gradleWrapper = Join-Path $repositoryRoot "gradlew.bat"

Push-Location -LiteralPath $repositoryRoot
try {
    & $gradleWrapper runMcpDiscovery --offline --console=plain
} finally {
    Pop-Location
}
if ($LASTEXITCODE -ne 0) {
    throw "MCP discovery smoke CLI failed with exit code $LASTEXITCODE."
}
