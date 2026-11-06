param([string]$JavaHome = 'D:/Java/jdk-17')
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$runDir = Join-Path $repo 'target/aigc-es'
$probe = [System.Net.Sockets.TcpClient]::new()
$occupied = $false
try { $probe.Connect('127.0.0.1', 8083); $occupied = $true }
catch [System.Net.Sockets.SocketException] { }
finally { $probe.Dispose() }
if ($occupied) { throw 'Port 8083 is occupied; retaining the running process.' }
$java = Join-Path $JavaHome 'bin/java.exe'
$jar = Join-Path $repo 'tj-search/target/tj-search.jar'
if (!(Test-Path -LiteralPath $java) -or !(Test-Path -LiteralPath $jar)) { throw 'Java or packaged search service missing.' }
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$arguments = @('-Xms128m', '-Xmx384m', '-jar', $jar, '--spring.profiles.active=local',
    '--server.port=8083', '--spring.cloud.nacos.config.namespace=tianji-local-boot3',
    '--spring.cloud.nacos.discovery.namespace=tianji-local-boot3')
$quoted = $arguments | ForEach-Object { '"' + $_.Replace('"', '\"') + '"' }
$process = Start-Process -FilePath $java -ArgumentList $quoted -WorkingDirectory $repo -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $runDir 'search.stdout.log') `
    -RedirectStandardError (Join-Path $runDir 'search.stderr.log')
@{ pid = $process.Id; started = $process.StartTime.ToUniversalTime().ToString('o'); port = 8083 } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runDir 'search-process.json') -Encoding UTF8
Write-Output "Started search service, PID $($process.Id); inspect target/aigc-es/search.stdout.log for readiness."
