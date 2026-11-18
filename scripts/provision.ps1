param(
    [string]$Target,
    [string]$ApkPath,
    [string]$ServerName,
    [string]$PackageName = "com.reiniertutoriales.vireoairtv"
)

$ErrorActionPreference = "Stop"

function Invoke-Adb {
    param([string[]]$ArgsList)
    $fullArgs = @()
    if ($Target) { $fullArgs += @("-s", $Target) }
    $fullArgs += $ArgsList
    $output = & adb @fullArgs 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb $($fullArgs -join ' ') failed with exit code $LASTEXITCODE`n$output"
    }
    return ($output -join "`n")
}

if ($Target -and $Target -match ":") {
    & adb connect $Target
}

Invoke-Adb @("wait-for-device") | Out-Null

if ($ApkPath) {
    if (-not (Test-Path $ApkPath)) {
        throw "APK not found: $ApkPath"
    }
    Invoke-Adb @("install", "-r", "-g", $ApkPath) | Out-Null
}

# Permitir abrir la app al conectar y evitar restricciones de batería en TV
Invoke-Adb @("shell", "appops", "set", $PackageName, "SYSTEM_ALERT_WINDOW", "allow") | Out-Null
Invoke-Adb @("shell", "dumpsys", "deviceidle", "whitelist", "+$PackageName") | Out-Null

# Ajustes iniciales vía SettingsReceiver (requiere android.permission.DUMP, concedido a adb shell)
$settings = @(
    @("auto_start", "true"),
    @("boot_auto_start", "true"),
    @("run_in_background", "true"),
    @("launch_on_connect", "true")
)
if ($ServerName) {
    $settings += ,@("server_name", $ServerName)
}

foreach ($pair in $settings) {
    $out = Invoke-Adb @(
        "shell", "am", "broadcast",
        "-a", "io.github.jqssun.airplay.SET_SETTING",
        "-n", "$PackageName/io.github.jqssun.airplay.SettingsReceiver",
        "--es", "key", $pair[0],
        "--es", "value", $pair[1]
    )
    if ($out -notmatch "result=-1") {
        throw "SettingsReceiver rejected $($pair[0])=$($pair[1]):`n$out"
    }
}

Write-Host "Provisioning completed for $PackageName"
