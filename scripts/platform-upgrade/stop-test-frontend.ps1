param([ValidateSet('dev', 'preview')][string[]]$Services = @('dev', 'preview'))
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
foreach ($service in $Services) {
    $name = if ($service -eq 'dev') { 'vite' } else { 'preview' }
    $path = Join-Path $repo "target/platform-upgrade/frontend/$name-process.json"
    if (!(Test-Path -LiteralPath $path)) { continue }
    $record = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    $process = Get-Process -Id $record.pid -ErrorAction SilentlyContinue
    if ($process) {
        if ($record.owner -ne 'tianji-platform-upgrade-test' -or
            [Math]::Abs(($process.StartTime.ToUniversalTime() - ([DateTime]$record.started).ToUniversalTime()).TotalSeconds) -gt 1) {
            throw 'Frontend process ownership/start time mismatch.'
        }
        $details = Get-CimInstance Win32_Process -Filter "ProcessId = $($record.pid)"
        if ($details.CommandLine -notlike '*vite/bin/vite.js*' -or $details.CommandLine -notlike "*--port $($record.port)*") {
            throw 'Frontend process command does not match its task manifest.'
        }
        Stop-Process -Id $record.pid
        Write-Output "Stopped task frontend $service PID $($record.pid)"
    }
    Remove-Item -LiteralPath $path
}
