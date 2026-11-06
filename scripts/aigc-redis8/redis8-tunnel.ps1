param(
    [ValidateSet('Start', 'Stop')][string]$Action = 'Start',
    [string]$SshHost = 'developer@tianji-dev.example.invalid',
    [string]$SshKey = (Join-Path $env:USERPROFILE '.ssh/id_ed25519'),
    [ValidateRange(1, 65535)][int]$LocalPort = 36379
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$manifestPath = Join-Path $repo 'target/aigc-redis8/redis8-tunnel.json'
$mapping = "127.0.0.1:${LocalPort}:127.0.0.1:36379"
if (Test-Path -LiteralPath $manifestPath) {
    $record = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    $process = Get-Process -Id $record.pid -ErrorAction SilentlyContinue
    if ($process -and $process.ProcessName -eq 'ssh' -and
        $process.StartTime.ToUniversalTime().Ticks -eq ([datetime]$record.started).ToUniversalTime().Ticks) {
        if ($Action -eq 'Stop') {
            Stop-Process -Id $process.Id
            Remove-Item -LiteralPath $manifestPath
            Write-Output 'Stopped the recorded Redis 8 tunnel.'
            return
        }
        if ($record.mapping -ne $mapping -or $record.host -ne $SshHost) {
            throw 'A different Redis 8 tunnel is already recorded; stop it first.'
        }
        Write-Output "Redis 8 tunnel already running, PID $($process.Id)."
        return
    }
}
if ($Action -eq 'Stop') { Write-Output 'No recorded Redis 8 tunnel is running.'; return }
$probe = [System.Net.Sockets.TcpClient]::new()
$occupied = $false
try { $probe.Connect('127.0.0.1', $LocalPort); $occupied = $true }
catch [System.Net.Sockets.SocketException] { }
finally { $probe.Dispose() }
if ($occupied) { throw "Local port $LocalPort is occupied; retaining its owner. Use -LocalPort to select another port." }
$sshArgs = @('-i', $SshKey, '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes',
    '-o', 'ExitOnForwardFailure=yes', '-o', 'ServerAliveInterval=15', '-o', 'ServerAliveCountMax=3',
    '-N', '-L', $mapping, $SshHost)
$quoted = $sshArgs | ForEach-Object { '"' + $_.Replace('"', '\"') + '"' }
$process = Start-Process ssh -ArgumentList $quoted -WindowStyle Hidden -PassThru
New-Item -ItemType Directory -Force -Path (Split-Path $manifestPath) | Out-Null
@{ pid = $process.Id; started = $process.StartTime.ToUniversalTime().ToString('o');
    mapping = $mapping; host = $SshHost } | ConvertTo-Json | Set-Content -LiteralPath $manifestPath -Encoding UTF8
for ($attempt = 0; $attempt -lt 20; $attempt++) {
    Start-Sleep -Milliseconds 250
    if ($process.HasExited) { throw 'Redis 8 SSH tunnel failed to start.' }
    $probe = [System.Net.Sockets.TcpClient]::new()
    try {
        $probe.Connect('127.0.0.1', $LocalPort)
        Write-Output "Redis 8 tunnel ready on 127.0.0.1:$LocalPort, PID $($process.Id)."
        return
    }
    catch [System.Net.Sockets.SocketException] { }
    finally { $probe.Dispose() }
}
throw 'Redis 8 tunnel did not become ready; inspect the recorded SSH process.'
