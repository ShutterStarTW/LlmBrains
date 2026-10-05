param(
    [string]$IdeaPath = "$env:LOCALAPPDATA\Programs\IntelliJ IDEA Ultimate",
    [string]$ScreenshotDir = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..\..")).Path
$gradleWrapper = Join-Path $repositoryRoot "gradlew.bat"
$ideaJava = Join-Path $IdeaPath "jbr\bin\java.exe"
$standalone = Join-Path $repositoryRoot "tmp\junit-libs\junit-platform-console-standalone-1.10.2.jar"

# tmp/ is not version-controlled: on a fresh clone, fetch the JUnit console launcher from Maven Central.
if (-not (Test-Path -LiteralPath $standalone -PathType Leaf)) {
    $standaloneUrl = "https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/1.10.2/junit-platform-console-standalone-1.10.2.jar"
    Write-Host "JUnit console launcher not found, downloading: $standaloneUrl"
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $standalone) | Out-Null
    try {
        Invoke-WebRequest -Uri $standaloneUrl -OutFile $standalone -UseBasicParsing
    } catch {
        Remove-Item -LiteralPath $standalone -Force -ErrorAction SilentlyContinue
        throw "Could not download the JUnit console launcher ($($_.Exception.Message)). Download it manually from $standaloneUrl to $standalone."
    }
}

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
        # Needed once any tested code loads a bundled SVG icon via IconLoader.getIcon (e.g. a
        # skill row icon) - without both, IconLoader's SVG path throws either
        # NoClassDefFoundError: com/intellij/util/lang/UrlClassLoader (missing platform-loader.jar)
        # or NoClassDefFoundError on com.github.weisj.jsvg... (missing intellij.libraries.jsvg.jar,
        # the actual SVG rasterizer) - the latter has shown up intermittently, most likely
        # depending on AWT layout timing for whether an icon's size is queried during the test.
        "platform-loader.jar"
        "intellij.libraries.jsvg.jar"
        "intellij.platform.core.jar"
        "intellij.platform.projectModel.jar"
        "intellij.platform.ide.jar"
        "intellij.platform.util.ui.jar"
        "intellij.platform.editor.ui.jar"
        "intellij.platform.core.ui.jar"
        "intellij.libraries.caffeine.jar"
        "intellij.libraries.fastutil.jar"
        "intellij.libraries.kotlinx.coroutines.core.jar"
        "intellij.libraries.aalto.xml.jar"
    ) | ForEach-Object { Join-Path $ideaLib $_ }

    # Full Swing panels expose types from several SDK modules through inherited methods.
    # Use the installed platform module set so reflective AWT inspection can resolve them.
    $platformJars = @($platformJars + @(Get-ChildItem -LiteralPath $ideaLib -Filter 'intellij.platform*.jar' | ForEach-Object FullName) | Select-Object -Unique)

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
    $javaOptions = @('-Djava.awt.headless=false', '-Dagenthub.test.mode=true')
    if ($ScreenshotDir) {
        New-Item -ItemType Directory -Force -Path $ScreenshotDir | Out-Null
        $resolvedScreenshotDir = (Resolve-Path -LiteralPath $ScreenshotDir).Path
        $javaOptions += "-Dagenthub.ui.screenshot.dir=$resolvedScreenshotDir"
    }
    & $ideaJava @javaOptions '--add-opens=java.desktop/javax.swing=ALL-UNNAMED' `
        '--add-opens=java.desktop/javax.swing.plaf.basic=ALL-UNNAMED' -jar $standalone execute `
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
