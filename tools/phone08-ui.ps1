param(
    [ValidateSet('emulator-5580','emulator-5582')][string]$Serial = 'emulator-5580',
    [ValidateSet(320,411)][int]$Width = 411,
    [ValidateSet('DARK','LIGHT')][string]$Theme = 'DARK',
    [ValidateSet('1','1.3')][string]$FontScale = '1',
    [string]$Capture = 'phone08',
    [switch]$Install
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if ($Capture -notmatch '^[A-Za-z0-9_-]+$') { throw 'Unsafe capture name' }
if ($Install) {
    & $adb -s $Serial install -r (Join-Path $root 'android\app\build\outputs\apk\debug\app-debug.apk')
    if ($LASTEXITCODE -ne 0) { throw 'App install failed; do not uninstall non-test targets' }
    & $adb -s $Serial install -r (Join-Path $root 'android\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk')
    if ($LASTEXITCODE -ne 0) { throw 'Test install failed' }
}
# Set actual viewport, not just a narrower Compose box.
& $adb -s $Serial shell wm size "${Width}x900"
& $adb -s $Serial shell wm density 160
$output = & $adb -s $Serial shell am instrument -w -r -e class dev.dshmobile.app.ui.chat.PhoneFeaturesTest -e phoneWidth $Width -e phoneScale $FontScale -e phoneTheme $Theme -e phoneCapture $Capture dev.dshmobile.app.test/androidx.test.runner.AndroidJUnitRunner 2>&1
$output | Write-Output
if ($LASTEXITCODE -ne 0 -or -not ($output -match 'OK \(12 tests\)') -or ($output -match 'FAILURES!!!')) { throw 'Phone Compose tests failed' }
