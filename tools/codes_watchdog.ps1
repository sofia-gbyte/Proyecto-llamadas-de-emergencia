param(
    [Parameter(Mandatory=$true)][int]$ParentPid,
    [int[]]$ProcessIds = @(),
    [int[]]$Ports = @(8000,6006)
)
$ErrorActionPreference='SilentlyContinue'
function Stop-Tree([int]$Pid) {
    if ($Pid -and $Pid -ne $PID) { taskkill.exe /PID $Pid /T /F | Out-Null }
}
try {
    while ($true) {
        $parent = Get-Process -Id $ParentPid -ErrorAction SilentlyContinue
        if (-not $parent) { break }
        Start-Sleep -Seconds 2
    }
}
finally {
    foreach ($p in $ProcessIds) { Stop-Tree $p }
    Start-Sleep -Milliseconds 500
    foreach ($port in $Ports) {
        try {
            $connections = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
            foreach ($c in $connections) { if ($c.OwningProcess -and $c.OwningProcess -ne $PID) { Stop-Tree $c.OwningProcess } }
        } catch {}
    }
}
