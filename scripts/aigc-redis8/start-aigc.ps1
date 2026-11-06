param(
    [string]$JavaHome = 'D:/Java/jdk-17',
    [ValidateSet('redis', 'elasticsearch')][string]$StoreType = 'redis'
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$runDir = Join-Path $repo 'target/aigc-redis8/runtime'
$jar = Join-Path $repo 'tj-aigc/target/tj-aigc.jar'
if (!(Test-Path -LiteralPath $jar)) { throw 'Package tj-aigc first.' }
$probe = [System.Net.Sockets.TcpClient]::new()
try { $probe.Connect('127.0.0.1', 8096); throw 'Port 8096 is occupied; retaining its owner.' }
catch [System.Net.Sockets.SocketException] { }
finally { $probe.Dispose() }
$names = @('DASHSCOPE_API_KEY', 'DASHSCOPE_BASE_URL', 'DEEPSEEK_API_KEY', 'TJ_AIGC_VECTOR_REDIS_PASSWORD')
$saved = @{}
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
New-Item -ItemType Directory -Force $runDir | Out-Null
try {
    foreach ($name in @('DASHSCOPE_API_KEY', 'DASHSCOPE_BASE_URL', 'DEEPSEEK_API_KEY')) {
        $value = [Environment]::GetEnvironmentVariable($name, 'Process')
        if ([string]::IsNullOrWhiteSpace($value)) { $value = [Environment]::GetEnvironmentVariable($name, 'User') }
        if ([string]::IsNullOrWhiteSpace($value)) { $value = [Environment]::GetEnvironmentVariable($name, 'Machine') }
        if ([string]::IsNullOrWhiteSpace($value)) { throw "Missing environment variable: $name" }
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
    if ($StoreType -eq 'redis') {
        $env:TJ_AIGC_VECTOR_REDIS_PASSWORD = (Get-Content (Join-Path $repo 'target/aigc-redis8/private/password.txt') -Raw).Trim()
    }
    $arguments = @('-Xms64m', '-Xmx384m', '-XX:ActiveProcessorCount=2', '-Dfile.encoding=UTF-8', '-jar', $jar,
        '--spring.profiles.active=local', '--server.address=127.0.0.1', '--server.port=8096',
        '--spring.cloud.nacos.server-addr=127.0.0.1:8848',
        '--spring.cloud.nacos.config.namespace=tianji-local-boot3',
        '--spring.cloud.nacos.discovery.namespace=tianji-local-boot3',
        "--tj.ai.knowledge.store-type=$StoreType")
    $quoted = $arguments | ForEach-Object { '"' + $_.Replace('"', '\"') + '"' }
    $process = Start-Process (Join-Path $JavaHome 'bin/java.exe') -ArgumentList $quoted -WorkingDirectory $repo `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runDir 'aigc.stdout.log') `
        -RedirectStandardError (Join-Path $runDir 'aigc.stderr.log')
    @{ pid = $process.Id; started = $process.StartTime.ToUniversalTime().ToString('o');
       jar = $jar; port = 8096; storeType = $StoreType } | ConvertTo-Json |
        Set-Content (Join-Path $runDir 'process.json') -Encoding UTF8
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        Start-Sleep -Milliseconds 500
        $process.Refresh()
        if ($process.HasExited) { throw "AIGC exited; inspect logs under $runDir" }
        if (Select-String -LiteralPath (Join-Path $runDir 'aigc.stdout.log') -SimpleMatch 'Started AIGCApplication' -Quiet) {
            Write-Output "AIGC started with $StoreType vectors: PID $($process.Id), loopback port 8096"
            return
        }
    }
    throw "AIGC is still starting; inspect logs and the recorded PID under $runDir"
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}
