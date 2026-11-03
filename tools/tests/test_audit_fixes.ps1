$ErrorActionPreference = 'Stop'
$script:passed = 0
$script:skipped = 0
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

function Assert-Check {
    param([string]$Name, [bool]$Condition)

    if (-not $Condition) {
        throw "FAIL: $Name"
    }

    $script:passed++
    Write-Host "PASS: $Name"
}

$launcherPath = Join-Path $projectRoot 'tools\iniciar_codes_windows.ps1'
$watchdogPath = Join-Path $projectRoot 'tools\codes_watchdog.ps1'
$cleanupPath = Join-Path $projectRoot 'tools\limpiar_proyecto.ps1'
$launcherText = Get-Content -Path $launcherPath -Raw
$watchdogText = Get-Content -Path $watchdogPath -Raw
$cleanupText = Get-Content -Path $cleanupPath -Raw

foreach ($scriptPath in @($launcherPath, $watchdogPath, $cleanupPath)) {
    $tokens = $null
    $parseErrors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile(
        $scriptPath,
        [ref]$tokens,
        [ref]$parseErrors
    )
    Assert-Check "PowerShell syntax: $(Split-Path -Leaf $scriptPath)" ($parseErrors.Count -eq 0)
}

Assert-Check 'launcher contains no port-owner lookup or port-sweep cleanup' (
    $launcherText -notmatch 'Get-NetTCPConnection|OwningProcess|Stop-ProcesoHuerfanoEnPuerto'
)
Assert-Check 'launcher taskkill target is an explicitly owned root process ID' (
    $launcherText -match 'taskkill\.exe\s+/PID\s+\$RootProcessId\s+/T\s+/F' -and
    $launcherText -match 'Stop-OwnedProcessTree\s+`?\s*-RootProcessId\s+\$springProcess\.Id' -and
    $launcherText -match '\$springProcess\.StartTime\.ToUniversalTime\(\)\.Ticks'
)
Assert-Check 'launcher passes owned process identity to watchdog' (
    $launcherText.Contains("'-OwnedProcessIds', [string]`$springProcess.Id") -and
    $launcherText.Contains("'-OwnedProcessStartTimeUtcTicks', [string]`$springProcess.StartTime.ToUniversalTime().Ticks")
)
Assert-Check 'watchdog has no port-based discovery or termination' (
    $watchdogText -notmatch 'Get-NetTCPConnection|OwningProcess|\$Ports\b'
)
Assert-Check 'watchdog receives owned child IDs and verifies launcher identity' (
    $watchdogText -match '\$OwnedProcessIds\b' -and
    $watchdogText -match '\$OwnedProcessStartTimeUtcTicks\b' -and
    $watchdogText -match '\$ParentProcessStartTimeUtcTicks\b' -and
    $watchdogText -match 'taskkill\.exe\s+/PID\s+\$RootProcessId\s+/T\s+/F' -and
    $watchdogText -notmatch '(?im)\[int\]\s*\$Pid\b'
)
Assert-Check 'lightweight ZIP excludes backup/log folders and local log files' (
    $cleanupText -match 'Join-Path\s+\$root\s+''backups''' -and
    $cleanupText -match 'Join-Path\s+\$root\s+''logs''' -and
    $cleanupText -match "'app-run\.log'" -and
    $cleanupText -match "'\*\.log'"
)

# Exercise the real cleanup/ZIP path against a synthetic project in TEMP.
# The production workspace is never passed to -Borrar by this test.
$tempParent = Join-Path $env:TEMP ('codes-audit-fixes-' + [guid]::NewGuid().ToString('N'))
$sandboxRoot = Join-Path $tempParent 'project'
$sandboxTools = Join-Path $sandboxRoot 'tools'
$portProbe = New-Object System.Net.Sockets.TcpClient
$portInUse = $false
try {
    try {
        $portProbe.Connect('127.0.0.1', 8000)
        $portInUse = $true
    } catch {
        $portInUse = $false
    }
} finally {
    $portProbe.Dispose()
}

if ($portInUse) {
    $script:skipped++
    Write-Host 'SKIP: synthetic ZIP integration test (port 8000 is occupied; avoiding cleanup prompt).'
} else {
    try {
        New-Item -ItemType Directory -Force -Path $sandboxTools | Out-Null
        Copy-Item -LiteralPath $cleanupPath -Destination (Join-Path $sandboxTools 'limpiar_proyecto.ps1')
        foreach ($relativePath in @(
            'backups\backup-marker.txt',
            'logs\session.log',
            'tools\asr\asr.log',
            'app-run.log',
            'README.md'
        )) {
            $filePath = Join-Path $sandboxRoot $relativePath
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $filePath) | Out-Null
            Set-Content -Path $filePath -Value "test fixture for $relativePath" -Encoding UTF8
        }

        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $sandboxTools 'limpiar_proyecto.ps1') -Borrar -Comprimir
        $archiveExitCode = $LASTEXITCODE
        Assert-Check 'synthetic project packaged successfully' ($archiveExitCode -eq 0)

        $archivePath = Get-ChildItem -Path $tempParent -Filter 'CODES_liviano_*.zip' -File |
            Select-Object -First 1 -ExpandProperty FullName
        Assert-Check 'synthetic lightweight ZIP was created' (-not [string]::IsNullOrWhiteSpace($archivePath))

        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $archive = [System.IO.Compression.ZipFile]::OpenRead($archivePath)
        try {
            $entryNames = @($archive.Entries | ForEach-Object { $_.FullName.Replace('\', '/') })
        } finally {
            $archive.Dispose()
        }

        Assert-Check 'ZIP retains ordinary project files' ($entryNames -contains 'project/README.md')
        Assert-Check 'ZIP omits backups, logs, and root app-run.log' (
            -not ($entryNames | Where-Object { $_ -match '(^|/)backups/' }) -and
            -not ($entryNames | Where-Object { $_ -match '(^|/)logs/' }) -and
            -not ($entryNames | Where-Object { $_ -match '(^|/)app-run\.log$' }) -and
            -not ($entryNames | Where-Object { $_ -match '\.log$' })
        )
    } finally {
        if (Test-Path $tempParent) {
            Remove-Item -LiteralPath $tempParent -Recurse -Force
        }
    }
}

# Exercise the watchdog's process-identity guard with an unrelated live process.
$parentProcess = $null
$unrelatedProcess = $null
$watchdogProcess = $null
try {
    $parentProcess = Start-Process -FilePath (Join-Path $PSHOME 'powershell.exe') `
        -ArgumentList @('-NoProfile', '-Command', 'Start-Sleep -Seconds 30') -PassThru -WindowStyle Hidden
    $unrelatedProcess = Start-Process -FilePath (Join-Path $PSHOME 'powershell.exe') `
        -ArgumentList @('-NoProfile', '-Command', 'Start-Sleep -Seconds 30') -PassThru -WindowStyle Hidden
    $parentStartTicks = (Get-Process -Id $parentProcess.Id).StartTime.ToUniversalTime().Ticks
    $unrelatedStartTicks = (Get-Process -Id $unrelatedProcess.Id).StartTime.ToUniversalTime().Ticks
    $watchdogArguments = @(
        '-NoProfile',
        '-ExecutionPolicy', 'Bypass',
        '-File', "`"$watchdogPath`"",
        '-ParentProcessId', [string]$parentProcess.Id,
        '-ParentProcessStartTimeUtcTicks', [string]$parentStartTicks,
        '-OwnedProcessIds', [string]$unrelatedProcess.Id,
        '-OwnedProcessStartTimeUtcTicks', [string]($unrelatedStartTicks + 1)
    )
    $watchdogProcess = Start-Process -FilePath (Join-Path $PSHOME 'powershell.exe') `
        -ArgumentList $watchdogArguments -PassThru -WindowStyle Hidden
    Start-Sleep -Milliseconds 500
    Stop-Process -Id $parentProcess.Id -Force
    $watchdogExited = $watchdogProcess.WaitForExit(7000)
    $unrelatedStillRunning = $null -ne (Get-Process -Id $unrelatedProcess.Id -ErrorAction SilentlyContinue)
    Assert-Check 'watchdog exits after its original launcher exits' $watchdogExited
    Assert-Check 'watchdog leaves an unrelated PID alive when its start-time identity differs' $unrelatedStillRunning
} finally {
    foreach ($process in @($watchdogProcess, $parentProcess, $unrelatedProcess)) {
        if ($process) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        }
    }
}

Write-Host "Results: $script:passed passed, 0 failed, $script:skipped skipped."
