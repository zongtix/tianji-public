param(
    [Parameter(Mandatory = $true)][string]$SshHost,
    [Parameter(Mandatory = $true)][string]$SshKey
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$manifestPath = Join-Path $repo 'target/platform-upgrade/tunnels.json'
New-Item -ItemType Directory -Force -Path (Split-Path $manifestPath) | Out-Null
$records = @()
if (Test-Path -LiteralPath $manifestPath) { $records = @(Get-Content $manifestPath -Raw | ConvertFrom-Json) }
foreach ($forward in @(@{ local = 16379; remote = 16379 }, @{ local = 26379; remote = 26379 }, @{ local = 8091; remote = 19091 })) {
    $probe = [System.Net.Sockets.TcpClient]::new()
    $listening = $false
    try { $probe.Connect('127.0.0.1', $forward.local); $listening = $true }
    catch [System.Net.Sockets.SocketException] { }
    finally { $probe.Dispose() }
    if ($listening) { Write-Output "Port $($forward.local) already listens; retaining the existing connection."; continue }
    $mapping = "127.0.0.1:$($forward.local):127.0.0.1:$($forward.remote)"
    $sshArgs = @('-i', $SshKey, '-o', 'BatchMode=yes', '-o', 'ExitOnForwardFailure=yes',
        '-o', 'ServerAliveInterval=15', '-o', 'ServerAliveCountMax=3', '-N', '-L', $mapping, $SshHost)
    $quoted = $sshArgs | ForEach-Object { '"' + $_.Replace('"', '\"') + '"' }
    $process = Start-Process ssh -ArgumentList $quoted -WindowStyle Hidden -PassThru
    Start-Sleep -Milliseconds 250
    if ($process.HasExited) { throw "Test tunnel $mapping failed to start" }
    $records += [pscustomobject]@{ pid = $process.Id; started = $process.StartTime.ToUniversalTime().ToString('o'); mapping = $mapping; host = $SshHost }
    $records | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
    Write-Output "Started test tunnel $mapping, PID $($process.Id)"
}
