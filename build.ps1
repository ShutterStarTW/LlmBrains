#!/usr/bin/env pwsh
# Windows counterpart to build.sh — produces the plugin ZIP in build/distributions.

$ErrorActionPreference = "Stop"

# Fall back to the documented GraalVM toolchain only when JAVA_HOME is unset.
if (-not $env:JAVA_HOME) {
    $jdk = "$env:USERPROFILE\.jdks\graalvm-jdk-24.0.2"
    if (Test-Path $jdk) { $env:JAVA_HOME = $jdk }
}
if ($env:JAVA_HOME) { $env:Path = "$env:JAVA_HOME\bin;$env:Path" }

$version = (Get-Content VERSION.md -Raw).Trim()
Write-Host ">>> Build version $version"

& java -jar gradle/wrapper/gradle-wrapper.jar buildPlugin
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Get-ChildItem build/distributions | Sort-Object LastWriteTime
