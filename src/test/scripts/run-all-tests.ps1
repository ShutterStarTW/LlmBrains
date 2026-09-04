param(
    [string]$IdeaPath = "$env:LOCALAPPDATA\Programs\IntelliJ IDEA Ultimate"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..\..")).Path
$gradleWrapper = Join-Path $repositoryRoot "gradlew.bat"
$ideaJava = Join-Path $IdeaPath "jbr\bin\java.exe"
$standalone = Join-Path $repositoryRoot "tmp\junit-libs\junit-platform-console-standalone-1.10.2.jar"

foreach ($requiredPath in @($gradleWrapper, $ideaJava, $standalone)) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        throw "Required test dependency is missing: $requiredPath"
    }
}

Push-Location -LiteralPath $repositoryRoot
try {
    & $gradleWrapper testClasses --offline --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw "Test compilation failed with exit code $LASTEXITCODE."
    }

    $mainOut = (Resolve-Path -LiteralPath ".\build\classes\kotlin\main").Path
    $testOut = (Resolve-Path -LiteralPath ".\build\classes\kotlin\test").Path
    $mainResources = (Resolve-Path -LiteralPath ".\build\resources\main").Path
    $testResources = (Resolve-Path -LiteralPath ".\build\resources\test").Path
    $ideaLib = Join-Path $IdeaPath "lib"
    $kotlinLib = Join-Path $IdeaPath "plugins\Kotlin\kotlinc\lib\kotlin-stdlib.jar"
    $platformJars = @(
        "annotations.jar"
        "util.jar"
        "util-8.jar"
        "util_rt.jar"
        "intellij.platform.core.jar"
        "intellij.platform.projectModel.jar"
        "intellij.platform.ide.jar"
        "intellij.platform.util.ui.jar"
        "intellij.libraries.fastutil.jar"
        "intellij.libraries.kotlinx.coroutines.core.jar"
        "intellij.libraries.aalto.xml.jar"
    ) | ForEach-Object { Join-Path $ideaLib $_ }

    $classpathEntries = @(
        $testOut
        $mainOut
        $mainResources
        $testResources
        $kotlinLib
    ) + $platformJars
    foreach ($entry in $classpathEntries) {
        if (-not (Test-Path -LiteralPath $entry)) {
            throw "Required test classpath entry is missing: $entry"
        }
    }

    $classpath = $classpathEntries -join ";"
    & $ideaJava '-Djava.awt.headless=false' -jar $standalone execute `
        --class-path $classpath `
        --scan-class-path=$testOut `
        --details=summary `
        --disable-banner `
        --fail-if-no-tests
    if ($LASTEXITCODE -ne 0) {
        throw "JUnit test run failed with exit code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}
