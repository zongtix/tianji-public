param([string[]]$Services = @('user', 'auth', 'gateway', 'learning', 'remark', 'promotion', 'course', 'search', 'trade', 'exam', 'data', 'aigc'))
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$manifestPath = Join-Path $repo 'target/platform-upgrade/processes.json'
if (!(Test-Path -LiteralPath $manifestPath)) { return }
$remaining = @()
foreach ($record in @(Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json)) {
    if ($record.service -notin $Services) { $remaining += $record; continue }
    $process = Get-Process -Id $record.pid -ErrorAction SilentlyContinue
    if (!$process) { continue }
    # PowerShell 7 can deserialize ISO strings as DateTime; preserve its UTC Kind.
    $started = ([DateTime]$record.started).ToUniversalTime()
    if ([Math]::Abs(($process.StartTime.ToUniversalTime() - $started).TotalSeconds) -gt 1) {
        throw "PID $($record.pid) was reused; refusing to stop it."
    }
    $details = Get-CimInstance Win32_Process -Filter "ProcessId = $($record.pid)"
    if ($details.CommandLine -notlike '*--spring.cloud.nacos.discovery.namespace=tianji-platform-upgrade-test*' -or
        $details.CommandLine -notlike "*$($record.jar)*") {
        throw "Process $($record.pid) is not a verified task service."
    }
    Stop-Process -Id $record.pid
    Write-Output "Stopped isolated $($record.service) process $($record.pid)"
}
ConvertTo-Json -InputObject @($remaining) -Depth 4 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
