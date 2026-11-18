param(
    [string]$Target,
    [string]$ApkPath,
    [string]$ServerName,
    [string]$PackageName = "com.reiniertutoriales.vireoairtv"
)

$ErrorActionPreference = "Stop"

# Windows PowerShell 5.1 turns any stderr line of a native command into a terminating error under
# "Stop" (adb prints "* daemon started successfully" there), so run adb with "Continue"
function Invoke-Native {
    param([string[]]$ArgsList)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try { return (& adb @ArgsList 2>&1 | ForEach-Object { "$_" }) }
    finally { $ErrorActionPreference = $prev }
}

function Invoke-Adb {
    param([string[]]$ArgsList)
    $fullArgs = @()
    if ($Target) { $fullArgs += @("-s", $Target) }
    $fullArgs += $ArgsList
    $output = Invoke-Native $fullArgs
    if ($LASTEXITCODE -ne 0) {
        throw "adb $($fullArgs -join ' ') failed with exit code $LASTEXITCODE`n$output"
    }
    return ($output -join "`n")
}

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb not found. Install Android SDK Platform-Tools and open a new terminal."
}

if ($Target -and $Target -match ":") {
    # adb connect exits 0 even when it fails; check its message instead
    $connect = (Invoke-Native @("connect", $Target)) -join "`n"
    Write-Host $connect
    if ($connect -match "failed to authenticate") {
        Write-Host "Accept `"Allow debugging`" on the TV; waiting..."
    } elseif ($connect -notmatch "connected to") {
        throw "Cannot reach $Target. Check the IP and port and that debugging is on in the TV."
    }
}

Invoke-Adb @("wait-for-device") | Out-Null

if ($ApkPath) {
    if (-not (Test-Path $ApkPath)) {
        throw "APK not found: $ApkPath"
    }
    Invoke-Adb @("install", "-r", "-g", $ApkPath) | Out-Null
}

# Runtime permissions, also when the APK was installed earlier without -g; granted before the first
# launch so the TV shows no permission dialogs
$sdk = [int](Invoke-Adb @("shell", "getprop", "ro.build.version.sdk")).Trim()
function Grant-Permission {
    param([string]$Permission)
    try { Invoke-Adb @("shell", "pm", "grant", $PackageName, $Permission) | Out-Null }
    catch { Write-Warning "Could not grant ${Permission}: $_" }
}
if ($sdk -ge 33) { Grant-Permission "android.permission.POST_NOTIFICATIONS" }
if ($sdk -ge 37) { Grant-Permission "android.permission.ACCESS_LOCAL_NETWORK" }

# Permitir abrir la app al conectar y evitar restricciones de batería en TV
Invoke-Adb @("shell", "appops", "set", $PackageName, "SYSTEM_ALERT_WINDOW", "allow") | Out-Null
Invoke-Adb @("shell", "dumpsys", "deviceidle", "whitelist", "+$PackageName") | Out-Null

# A fresh install stays in the "stopped" state and receives no broadcasts (BOOT_COMPLETED,
# SettingsReceiver) until one of its components is launched once
Invoke-Adb @("shell", "am", "start", "-W", "-n", "$PackageName/io.github.jqssun.airplay.MainActivity") | Out-Null

# Ajustes iniciales vía SettingsReceiver (requiere android.permission.DUMP, concedido a adb shell)
$settings = @(
    @("auto_start", "true"),
    @("boot_auto_start", "true"),
    @("run_in_background", "true"),
    @("launch_on_connect", "true"),
    @("alac_enabled", "true")
)
if ($ServerName) {
    $settings += ,@("server_name", $ServerName)
}

# adb shell joins its arguments into one remote command line: single-quote the value so names
# with spaces or quotes arrive whole
foreach ($pair in $settings) {
    $quoted = "'" + ($pair[1] -replace "'", "'\''") + "'"
    $out = Invoke-Adb @(
        "shell", "am", "broadcast",
        "-a", "io.github.jqssun.airplay.SET_SETTING",
        "-n", "$PackageName/io.github.jqssun.airplay.SettingsReceiver",
        "--es", "key", $pair[0],
        "--es", "value", $quoted
    )
    if ($out -notmatch "result=-1") {
        throw "SettingsReceiver rejected $($pair[0])=$($pair[1]):`n$out"
    }
}

# the receiver runs as a service; leave the TV on its home screen
Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_HOME") | Out-Null

Write-Host "Provisioning completed for $PackageName"
