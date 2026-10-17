[CmdletBinding()]
param(
    [string]$JavaHome,
    [string]$AndroidSdk
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$androidRoot = Join-Path $repoRoot 'android'

if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\java.exe'))) {
    $candidate = 'C:\Program Files\Android\Android Studio\jbr'
    if (Test-Path (Join-Path $candidate 'bin\java.exe')) { $JavaHome = $candidate }
}
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\java.exe'))) { throw 'Set -JavaHome to a JDK 17 installation or install Android Studio.' }

if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_HOME }
if (-not $AndroidSdk) {
    $candidate = Join-Path $env:USERPROFILE 'Android'
    if (Test-Path (Join-Path $candidate 'platforms\android-36')) { $AndroidSdk = $candidate }
}
if (-not $AndroidSdk -or -not (Test-Path (Join-Path $AndroidSdk 'platforms\android-36'))) { throw 'Set -AndroidSdk to an SDK containing platform 36.' }

$env:JAVA_HOME = $JavaHome
$env:ANDROID_SDK_ROOT = $AndroidSdk
$env:ANDROID_HOME = $AndroidSdk
$env:Path = (Join-Path $JavaHome 'bin') + ';' + $env:Path
$wrapper = Join-Path $androidRoot 'gradlew.bat'
if (-not (Test-Path $wrapper)) { throw 'Gradle wrapper is missing from android/.' }

Push-Location $androidRoot
try {
    & $wrapper --no-daemon --console=plain clean assembleDebug testDebugUnitTest lintDebug
    if ($LASTEXITCODE -ne 0) { throw "Android verification failed with exit code $LASTEXITCODE." }
} finally { Pop-Location }
