# Development only: allow embedded TURN (3478) and relay ports (49152-49200) on Windows.
# Allows the physical Target phone on LAN to communicate with the embedded TURN server.
param(
    [switch]$CheckOnly,
    [switch]$Quiet,
    [switch]$ElevateIfNeeded = $true
)

$rules = @(
    @{ Name = "ARL Local TURN Server UDP"; LegacyNames = @("ARL Local TURN UDP 3478", "AndroidRemoteLab TURN UDP"); Protocol = "UDP"; Ports = "3478" },
    @{ Name = "ARL Local TURN Server TCP"; LegacyNames = @("ARL Local TURN TCP 3478", "AndroidRemoteLab TURN TCP"); Protocol = "TCP"; Ports = "3478" },
    @{ Name = "ARL Local TURN Relay UDP";  LegacyNames = @("AndroidRemoteLab TURN Relay UDP");                      Protocol = "UDP"; Ports = "49152-49200" }
)

# Detect active network category (e.g. Public, Private, DomainAuthenticated)
$profiles = Get-NetConnectionProfile -ErrorAction SilentlyContinue
$activeCategories = @($profiles | ForEach-Object { $_.NetworkCategory.ToString() } | Select-Object -Unique)
$activeDesc = if ($activeCategories.Count -gt 0) { $activeCategories -join ", " } else { "Unknown" }

$needsUpdate = $false
$details = @()

foreach ($rule in $rules) {
    $existing = Get-NetFirewallRule -DisplayName $rule.Name -ErrorAction SilentlyContinue
    if (-not $existing -and $rule.LegacyNames) {
        foreach ($legacy in $rule.LegacyNames) {
            $existing = Get-NetFirewallRule -DisplayName $legacy -ErrorAction SilentlyContinue
            if ($existing) { break }
        }
    }
    if (-not $existing) {
        $needsUpdate = $true
        $details += "$($rule.Name): NOT FOUND"
        continue
    }
    if (-not $existing.Enabled) {
        $needsUpdate = $true
        $details += "$($rule.Name): DISABLED"
        continue
    }
    $prof = $existing.Profile.ToString()
    if ($prof -ne "Any") {
        foreach ($cat in $activeCategories) {
            if ($prof -notmatch $cat.ToString()) {
                $needsUpdate = $true
                $details += "$($rule.Name): profile is '$prof', which excludes active network '$cat'"
            }
        }
    }
    if (-not $needsUpdate) {
        $details += "$($rule.Name): OK ($prof)"
    }
}

if ($CheckOnly) {
    if ($needsUpdate) {
        if (-not $Quiet) {
            Write-Host "[WARN] TURN firewall rules require configuration/update:"
            $details | ForEach-Object { Write-Host "  - $_" }
            Write-Host "  Active network profile: $activeDesc"
        }
        exit 1
    } else {
        if (-not $Quiet) {
            Write-Host "[OK] All TURN firewall rules exist, are enabled, and cover active network profiles ($activeDesc)."
        }
        exit 0
    }
}

if (-not $needsUpdate) {
    if (-not $Quiet) {
        Write-Host "[OK] TURN firewall rules already present, enabled, and cover active network profiles ($activeDesc)."
    }
    exit 0
}

# Elevation is required to add or modify Windows Firewall rules
$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if (-not $isAdmin) {
    if ($ElevateIfNeeded) {
        if (-not $Quiet) {
            Write-Host "Requesting Administrator privileges to configure local TURN firewall rules..."
        }
        try {
            $p = Start-Process powershell.exe -Verb RunAs -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`"" -PassThru -Wait
            if ($p.ExitCode -eq 0) {
                if (-not $Quiet) { Write-Host "[OK] Firewall rules configured via elevated prompt." }
                exit 0
            }
        } catch {
            # Elevation failed or was cancelled by user
        }
    }

    if (-not $Quiet) {
        Write-Warning "Administrator privileges are required to configure local TURN firewall rules."
        Write-Warning "Active network profile is '$activeDesc'. Existing rules exclude it or are missing."
        Write-Warning "Without this, the physical Target phone cannot gather typ=relay ICE candidates and WebRTC streaming will fail."
        Write-Warning "Please open an elevated PowerShell (Run as Administrator) and run:"
        Write-Warning "powershell -ExecutionPolicy Bypass -File `"$PSCommandPath`""
    }
    exit 2
}

# Apply or update rules with Profile Any
foreach ($rule in $rules) {
    # Check for legacy rules and clean up older restricted variants if needed
    if ($rule.LegacyNames) {
        foreach ($legacy in $rule.LegacyNames) {
            $legRule = Get-NetFirewallRule -DisplayName $legacy -ErrorAction SilentlyContinue
            if ($legRule -and $legRule.DisplayName -ne $rule.Name -and $legRule.Profile -ne "Any") {
                Remove-NetFirewallRule -DisplayName $legacy -ErrorAction SilentlyContinue | Out-Null
            }
        }
    }

    $existing = Get-NetFirewallRule -DisplayName $rule.Name -ErrorAction SilentlyContinue
    if ($existing) {
        Set-NetFirewallRule -DisplayName $rule.Name -Profile Any -Action Allow -Enabled True | Out-Null
        if (-not $Quiet) { Write-Host "[OK] Updated existing firewall rule to Profile Any: $($rule.Name)" }
    } else {
        New-NetFirewallRule -DisplayName $rule.Name -Direction Inbound -Action Allow -Protocol $rule.Protocol -LocalPort $rule.Ports -Profile Any | Out-Null
        if (-not $Quiet) { Write-Host "[OK] Added firewall rule (Profile Any): $($rule.Name)" }
    }
}

if (-not $Quiet) { Write-Host "[OK] Local TURN firewall rules successfully configured for Profile Any (active: $activeDesc)." }
exit 0
