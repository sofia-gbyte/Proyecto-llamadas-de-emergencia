param(
    [Parameter(Mandatory=$true)][int]$ParentProcessId,
    [Parameter(Mandatory=$true)][long]$ParentProcessStartTimeUtcTicks,
    [int[]]$OwnedProcessIds = @(),
    [long[]]$OwnedProcessStartTimeUtcTicks = @()
)
$ErrorActionPreference='SilentlyContinue'
function Stop-OwnedProcessTree {
    param(
        [int]$RootProcessId,
        [long]$ExpectedStartTimeUtcTicks
    )

    # Only IDs explicitly passed by the launcher are eligible for cleanup.
    # Do not infer ownership from a listening port: it may belong to another app.
    if ($RootProcessId -gt 0 -and $RootProcessId -ne $PID) {
        try {
            $rootProcess = Get-Process -Id $RootProcessId -ErrorAction Stop
            if ($rootProcess.StartTime.ToUniversalTime().Ticks -ne $ExpectedStartTimeUtcTicks) {
                return
            }
        } catch {
            return
        }
        taskkill.exe /PID $RootProcessId /T /F | Out-Null
    }
}

function Test-LauncherProcessAlive {
    try {
        $parentProcess = Get-Process -Id $ParentProcessId -ErrorAction Stop
    } catch {
        return $false
    }

    try {
        return ($parentProcess.StartTime.ToUniversalTime().Ticks -eq $ParentProcessStartTimeUtcTicks)
    } catch {
        # If identity cannot be verified, keep waiting rather than risking a
        # cleanup while the original launcher may still be alive.
        return $true
    }
}

try {
    while (Test-LauncherProcessAlive) {
        Start-Sleep -Seconds 2
    }
}
finally {
    for ($index = 0; $index -lt $OwnedProcessIds.Count; $index++) {
        if ($index -lt $OwnedProcessStartTimeUtcTicks.Count) {
            Stop-OwnedProcessTree `
                -RootProcessId $OwnedProcessIds[$index] `
                -ExpectedStartTimeUtcTicks $OwnedProcessStartTimeUtcTicks[$index]
        }
    }
}
