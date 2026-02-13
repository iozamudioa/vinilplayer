param(
    [switch]$Kill,
    [switch]$OnlyMediaReader
)

$all = Get-CimInstance Win32_Process
$pidSet = [System.Collections.Generic.HashSet[uint32]]::new()
$all | ForEach-Object { [void]$pidSet.Add([uint32]$_.ProcessId) }

$candidates = $all | Where-Object { $_.Name -ieq 'dotnet.exe' }

if ($OnlyMediaReader) {
    $candidates = $candidates | Where-Object { $_.CommandLine -match 'media-reader' }
}

$orphans = $candidates | Where-Object {
    $_.ParentProcessId -gt 0 -and -not $pidSet.Contains([uint32]$_.ParentProcessId)
}

if (-not $orphans -or $orphans.Count -eq 0) {
    Write-Host "No se encontraron procesos dotnet huérfanos."
    exit 0
}

$orphans | Select-Object ProcessId, ParentProcessId, CreationDate, CommandLine | Format-Table -AutoSize

if ($Kill) {
    foreach ($p in $orphans) {
        try {
            Stop-Process -Id $p.ProcessId -Force -ErrorAction Stop
            Write-Host "Killed PID $($p.ProcessId)"
        } catch {
            Write-Warning "No se pudo cerrar PID $($p.ProcessId): $($_.Exception.Message)"
        }
    }
} else {
    Write-Host ""
    Write-Host "Modo diagnóstico. Para cerrar huérfanos usa: .\scripts\cleanup-dotnet-orphans.ps1 -Kill"
}
