param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [string]$AdminSerial = "emulator-5554",
    [string]$TargetSerial = "6dd5235d",
    [string]$AdminPkg = "lab.arl.admin",
    [string]$TargetPkg = "lab.arl.target",
    [string]$Adb = "D:\Andriod\Sdk\platform-tools\adb.exe",
    [string]$ApiBase = "http://127.0.0.1:8080",
    [string]$TargetApi = ""
)

$ErrorActionPreference = "Continue"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$e2eDir = Join-Path $Root "logs\e2e\$stamp"
New-Item -ItemType Directory -Force -Path $e2eDir | Out-Null
$env:ARL_E2E_DIR = $e2eDir

function Save-Shot([string]$Serial, [string]$Name) {
    $path = Join-Path $e2eDir $Name
    cmd /c "`"$Adb`" -s $Serial exec-out screencap -p > `"$path`"" 2>$null
    return $path
}

function Tap-Text([string]$Serial, [string]$Text) {
    & "$Root\scripts\adb-tap-text.ps1" -Serial $Serial -Text $Text 2>$null
}

function Wait-Log([string]$Serial, [string]$Pattern, [int]$TimeoutSec = 120) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $hit = & $Adb -s $Serial logcat -d -v brief ARL-WebRTC:I "${AdminPkg}:I" "${TargetPkg}:I" *:S 2>$null |
            Select-String -Pattern $Pattern -Quiet
        if ($hit) { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

Write-Host ""
Write-Host "E2E run artifacts: $e2eDir"
Write-Host ""

Save-Shot $AdminSerial "admin-before.png" | Out-Null

$email = $env:E2E_TEST_EMAIL
$password = $env:E2E_TEST_PASSWORD
if (-not $email -or -not $password) {
    Write-Host "E2E_TEST_EMAIL and E2E_TEST_PASSWORD not set — Admin sign-in must be done manually."
} else {
    Write-Host "Automating Admin sign-in..."
    Start-Sleep -Seconds 3
    Tap-Text $AdminSerial "SIGN IN" | Out-Null
    Start-Sleep -Seconds 1
    & $Adb -s $AdminSerial shell input text $email.Replace("@", "\@") 2>$null
    & $Adb -s $AdminSerial shell input keyevent 61 2>$null
    & $Adb -s $AdminSerial shell input text $password 2>$null
    Tap-Text $AdminSerial "SIGN IN" | Out-Null
    Start-Sleep -Seconds 3
}

if (-not $TargetApi) {
    $lan = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -match '^(192\.168\.|10\.)' -and $_.IPAddress -ne '10.0.2.2' } |
        Select-Object -First 1).IPAddress
    if ($lan) { $TargetApi = "http://${lan}:8080" } else { $TargetApi = "http://192.168.1.18:8080" }
}

if (-not $env:ARL_SKIP_PAIRING) {
    Write-Host "Issuing pairing session (Target API: $TargetApi)..."
    $pairJson = & node "$Root\scripts\e2e-setup.mjs" 2>$1 | Select-Object -Last 1
    try {
        $pair = $pairJson | ConvertFrom-Json
        $uri = $pair.pairUri
        Write-Host "Opening pairing URI on Target..."
        & $Adb -s $TargetSerial shell am start -a android.intent.action.VIEW -d $uri $TargetPkg 2>$null
        Start-Sleep -Seconds 2
        Tap-Text $TargetSerial "Claim pairing" | Out-Null
        Start-Sleep -Seconds 2
        Tap-Text $TargetSerial "I authorize enrollment" | Out-Null
        Start-Sleep -Seconds 5
    } catch {
        Write-Host "Pairing automation skipped or failed — enroll Target manually if needed."
    }
}

Write-Host ""
Write-Host "MANUAL STEPS (unavoidable Android consent):"
Write-Host "  1. Admin: select Target, press CONNECT"
Write-Host "  2. Target: Accept session"
Write-Host "  3. Target: Grant MediaProjection when prompted"
Write-Host "  4. Verify live screen in Admin MONITOR mode"
Write-Host "  5. Test MANAGED MODE, interaction, X, END SESSION"
Write-Host ""
Write-Host "Waiting for WebRTC pipeline (polling logcat, up to 3 min after you complete steps)..."
$waitResult = "MANUAL"
$deadline = (Get-Date).AddMinutes(5)
Write-Host "Complete manual steps on devices. Polling for first_frame_received (5 min max)..."
while ((Get-Date) -lt $deadline) {
    $log = & $Adb -s $AdminSerial logcat -d -v brief ARL-WebRTC:I *:S 2>$null | Out-String
    if ($log -match "first_frame_received") { $waitResult = "PASS"; Write-Host "[OK] first_frame_received detected"; break }
    Start-Sleep -Seconds 3
}
if ($waitResult -ne "PASS") {
    Write-Host "first_frame not detected automatically — press Enter after completing the manual test."
    Read-Host | Out-Null
}

& "$Root\scripts\e2e-finish.ps1" -Root $Root -AdminSerial $AdminSerial -TargetSerial $TargetSerial `
    -AdminPkg $AdminPkg -TargetPkg $TargetPkg -Adb $Adb -OutDir $e2eDir -WaitResult $waitResult
