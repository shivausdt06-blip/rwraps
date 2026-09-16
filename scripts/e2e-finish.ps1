param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [string]$AdminSerial = "emulator-5554",
    [string]$TargetSerial = "6dd5235d",
    [string]$AdminPkg = "lab.arl.admin",
    [string]$TargetPkg = "lab.arl.target",
    [string]$Adb = "",
    [string]$OutDir = "",
    [string]$WaitResult = ""
)

$ErrorActionPreference = "Continue"
$e2eDir = if ($OutDir) { $OutDir } else { Join-Path $Root "logs\e2e" }
New-Item -ItemType Directory -Force -Path $e2eDir | Out-Null

if (-not $Adb) {
    $sdk = $env:ANDROID_SDK_ROOT
    if (-not $sdk) { $sdk = $env:ANDROID_HOME }
    if (-not $sdk) { $sdk = "D:\Andriod\Sdk" }
    $Adb = Join-Path $sdk "platform-tools\adb.exe"
}

function Save-Logcat {
    param([string]$Serial, [string]$Pkg, [string]$Name)
    $out = Join-Path $e2eDir "$Name.log"
    & $Adb -s $Serial logcat -d -v time "ARL-WebRTC:I" "ARL-Interaction:I" "AndroidRuntime:E" "FATAL:E" "${Pkg}:I" "*:S" 2>$null |
        Out-File -FilePath $out -Encoding utf8
    return $out
}

function Test-Log {
    param([string]$Path, [string]$Pattern)
    if (-not (Test-Path $Path)) { return $false }
    return (Select-String -Path $Path -Pattern $Pattern -Quiet)
}

function Extract-Metric {
    param([string]$Path, [string]$Pattern)
    if (-not (Test-Path $Path)) { return $null }
    $m = Select-String -Path $Path -Pattern $Pattern | Select-Object -Last 1
    if (-not $m) { return $null }
    return $m.Line
}

Write-Host ""
Write-Host "Collecting E2E artifacts into $e2eDir ..."

$adminLog = Save-Logcat -Serial $AdminSerial -Pkg $AdminPkg -Name "admin"
$targetLog = Save-Logcat -Serial $TargetSerial -Pkg $TargetPkg -Name "target"

$shot = Join-Path $e2eDir "admin-final.png"
cmd /c "`"$Adb`" -s $AdminSerial exec-out screencap -p > `"$shot`"" 2>$null

$backendLog = Join-Path $e2eDir "backend.log"
$health = try {
    (Invoke-WebRequest -Uri "http://127.0.0.1:8080/health" -UseBasicParsing -TimeoutSec 5).Content
} catch {
    "health check failed: $($_.Exception.Message)"
}
@"
E2E artifact capture at $(Get-Date -Format o)
GET http://127.0.0.1:8080/health
$health

Note: Full backend runtime logs appear in the ARL Backend console window.
"@ | Out-File -FilePath $backendLog -Encoding utf8

$offerOk = Test-Log $adminLog "offer_create|signaling\.offer_sent"
$answerOk = Test-Log $adminLog "answer_received|signaling\.answer_received|remote_answer_set"
$iceOk = Test-Log $adminLog "ice_state CONNECTED|ice_connected|connection_state CONNECTED"
$vp8Ok = Test-Log $adminLog "VP8|CIT01|COT01"
$rtpInOk = Test-Log $adminLog "rtp_inbound"
$firstFrameOk = Test-Log $adminLog "first_frame_received"
$rtpOutOk = Test-Log $targetLog "rtp_outbound"
$captureOk = Test-Log $targetLog "first_frame_captured|frames_captured"
$fatalOk = (Test-Log $adminLog "FATAL EXCEPTION|AndroidRuntime.*FATAL") -or (Test-Log $targetLog "FATAL EXCEPTION|AndroidRuntime.*FATAL")

$failureStage = $null
if (-not $captureOk) { $failureStage = "CAPTURE" }
elseif (-not $rtpOutOk) { $failureStage = "RTP_SEND" }
elseif (-not $iceOk) { $failureStage = "TRANSPORT" }
elseif (-not $rtpInOk) { $failureStage = "TRANSPORT" }
elseif (-not $firstFrameOk) { $failureStage = "RENDER" }

$sessionId = (Select-String -Path $adminLog -Pattern "session=([a-f0-9-]+)" | Select-Object -Last 1).Matches.Groups[1].Value

function Get-TerminationReason {
    param([string]$AdminLog, [string]$TargetLog)
    $patterns = [ordered]@{
        SESSION_EXPIRED = "TIMED_OUT|session_terminal_TIMED_OUT|session\.timeout"
        SESSION_ENDED_BY_ADMIN = "Session terminated|session_ended|session_terminal_TERMINATED"
        SESSION_ENDED_BY_TARGET = "SESSION_ENDED|Session ended\."
        WEBSOCKET_TIMEOUT = "AUTH_TIMEOUT|WebSocket authentication timed out"
        WEBSOCKET_DISCONNECT = "Control channel|serverConnected.*false|ws_handler_failed"
        WEBRTC_FAILURE = "WebRTC FAILED|pc_failed|ice_failed"
        ICE_FAILURE = "ice_state FAILED|ICE could not connect"
        MEDIAPROJECTION_STOPPED = "capture_failed|MediaProjection|projection"
        TARGET_PROCESS_DEATH = "Process.*died|FATAL EXCEPTION.*$TargetPkg"
        ADMIN_PROCESS_DEATH = "FATAL EXCEPTION.*$AdminPkg"
        BACKEND_CLEANUP = "session\.timeout"
        TOKEN_EXPIRY = "UNAUTHORIZED|401|token.*expired"
    }
    foreach ($entry in $patterns.GetEnumerator()) {
        if ((Test-Log $AdminLog $entry.Value) -or (Test-Log $TargetLog $entry.Value)) {
            return $entry.Key
        }
    }
    if (Test-Log $AdminLog "session_terminal_") { return "SESSION_ENDED" }
    return $null
}

$terminationReason = Get-TerminationReason -AdminLog $adminLog -TargetLog $targetLog
$screenOffOk = Test-Log $adminLog "Screen sharing stopped"
$screenOnOk = Test-Log $adminLog "Screen sharing enabled"
$fullscreenOk = Test-Log $adminLog "EXIT FULL SCREEN|FULL SCREEN"

function Get-InteractionFailureStage {
    param([string]$AdminLog, [string]$TargetLog)
    $checks = [ordered]@{
        ADMIN_TOUCH = @{ ok = (Test-Log $AdminLog "touch_received"); stage = "ADMIN_TOUCH" }
        ADMIN_MAPPING = @{ ok = (Test-Log $AdminLog "mapped_coordinates"); stage = "ADMIN_MAPPING" }
        ADMIN_COMMAND_CREATION = @{ ok = (Test-Log $AdminLog "interaction_command_created"); stage = "ADMIN_COMMAND_CREATION" }
        ADMIN_WEBSOCKET_SEND = @{ ok = (Test-Log $AdminLog "interaction_sent"); stage = "ADMIN_WEBSOCKET_SEND" }
        TARGET_RECEIVE = @{ ok = (Test-Log $TargetLog "interaction_received"); stage = "TARGET_RECEIVE" }
        TARGET_VALIDATION = @{ ok = (Test-Log $TargetLog "interaction_authorized"); stage = "TARGET_VALIDATION" }
        ACCESSIBILITY_SERVICE = @{ ok = (Test-Log $TargetLog "accessibility_dispatch_attempt"); stage = "ACCESSIBILITY_SERVICE" }
        GESTURE_DISPATCH = @{ ok = (Test-Log $TargetLog "gesture_dispatch_success"); stage = "GESTURE_DISPATCH" }
    }
    foreach ($entry in $checks.GetEnumerator()) {
        if (-not $entry.Value.ok) { return $entry.Value.stage }
    }
    if (Test-Log $TargetLog "gesture_dispatch_failure") { return "GESTURE_DISPATCH" }
    if (Test-Log $TargetLog "interaction_rejected") { return "TARGET_VALIDATION" }
    if (Test-Log $AdminLog "MODE_VIEW_ONLY|Remote interaction is disabled") { return "BACKEND_AUTHORIZATION" }
    return $null
}

$interactionFailureStage = Get-InteractionFailureStage -AdminLog $adminLog -TargetLog $targetLog
$managedInteractionOk = (Test-Log $adminLog "interaction_sent") -and (Test-Log $targetLog "gesture_dispatch_success")

$summary = [ordered]@{
    result = if ($fatalOk) { "FAIL" } elseif ($firstFrameOk -and $iceOk -and $rtpInOk -and $rtpOutOk) { "PASS" } else { "INCOMPLETE" }
    sessionId = if ($sessionId) { $sessionId } else { $null }
    monitor = @{ video = $firstFrameOk; interaction = $false }
    managed = @{ video = $firstFrameOk; interaction = $managedInteractionOk }
    interactionFailureStage = $interactionFailureStage
    exitManaged = @{ videoPreserved = $null; sessionPreserved = $null }
    target = @{
        framesCaptured = (Extract-Metric $targetLog "frames_captured|first_frame_captured")
        rtpOutbound = (Extract-Metric $targetLog "rtp_outbound")
    }
    admin = @{
        rtpInbound = (Extract-Metric $adminLog "rtp_inbound")
        firstFrame = $firstFrameOk
    }
    ice = if ($iceOk) { "CONNECTED" } else { "UNKNOWN" }
    codec = if ($vp8Ok) { "VP8" } else { "UNKNOWN" }
    failureStage = $failureStage
    terminationReason = $terminationReason
    screenSharing = @{ off = $screenOffOk; on = $screenOnOk }
    fullscreen = $fullscreenOk
    artifacts = @{
        dir = $e2eDir
        adminLog = $adminLog
        targetLog = $targetLog
        backendLog = $backendLog
        adminScreenshot = $shot
    }
    waitResult = $WaitResult
}

$summaryPath = Join-Path $e2eDir "test-summary.json"
$summary | ConvertTo-Json -Depth 6 | Out-File -FilePath $summaryPath -Encoding utf8

Write-Host ""
Write-Host "============================================================"
Write-Host " ANDROID REMOTE LAB — E2E RESULT"
Write-Host "============================================================"
Write-Host ""
Write-Host "Result: $($summary.result)"
Write-Host "Failure stage: $(if ($failureStage) { $failureStage } else { 'none detected' })"
Write-Host ""
Write-Host "Target: $TargetSerial"
Write-Host "Admin:  $AdminSerial"
Write-Host ""
Write-Host "Artifacts:"
Write-Host "  $e2eDir"
Write-Host "  test-summary.json"
Write-Host "  admin-final.png"
Write-Host ""
Write-Host "Pipeline:"
Write-Host "  CAPTURE:      $(if ($captureOk) { 'PASS' } else { 'FAIL/UNKNOWN' })"
Write-Host "  RTP SEND:     $(if ($rtpOutOk) { 'PASS' } else { 'FAIL/UNKNOWN' })"
Write-Host "  ICE/DTLS:     $(if ($iceOk) { 'PASS' } else { 'FAIL/UNKNOWN' })"
Write-Host "  RTP RECEIVE:  $(if ($rtpInOk) { 'PASS' } else { 'FAIL/UNKNOWN' })"
Write-Host "  RENDER:       $(if ($firstFrameOk) { 'PASS' } else { 'FAIL/UNKNOWN' })"
Write-Host "  OFFER:        $(if ($offerOk) { 'PASS' } else { 'FAIL' })"
Write-Host "  ANSWER:       $(if ($answerOk) { 'PASS' } else { 'FAIL' })"
Write-Host "============================================================"
Write-Host ""

if ($summary.result -eq "INCOMPLETE") {
    Write-Host "MANUAL VERIFICATION REQUIRED — see logs in $e2eDir"
}
