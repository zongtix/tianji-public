param(
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [ValidateSet('user', 'auth', 'gateway', 'learning', 'remark', 'promotion', 'course', 'search', 'trade', 'exam', 'data', 'aigc')]
    [string[]]$Services = @('user', 'auth', 'gateway'),
    [ValidateSet('old', 'boot3')][string]$Platform = 'boot3',
    [ValidateSet('exam')][string[]]$SeataServices = @()
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$runDir = Join-Path $repo 'target/platform-upgrade'
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$java = Join-Path $JavaHome 'bin/java.exe'
if (!(Test-Path -LiteralPath $java)) { throw "Java not found: $java" }
$namespace = if ($Platform -eq 'boot3') { 'tianji-platform-upgrade-test-boot3' } else { 'tianji-platform-upgrade-test' }
$redisPrefix = if ($Platform -eq 'boot3') { 'spring.data.redis' } else { 'spring.redis' }
$manifestPath = Join-Path $runDir 'processes.json'
$manifest = @()
if (Test-Path -LiteralPath $manifestPath) {
    $manifest = @(Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json)
}
$ports = @{ user = 18082; auth = 18081; gateway = 11010; learning = 18090; remark = 18093; promotion = 18094; course = 18086; search = 18083; trade = 18088; exam = 18089; data = 18095; aigc = 18096 }
$modules = @{ auth = 'tj-auth/tj-auth-service'; user = 'tj-user'; gateway = 'tj-gateway'; learning = 'tj-learning'; remark = 'tj-remark'; promotion = 'tj-promotion'; course = 'tj-course'; search = 'tj-search'; trade = 'tj-trade'; exam = 'tj-exam'; data = 'tj-data'; aigc = 'tj-aigc' }
foreach ($service in $Services) {
    $seataEnabled = if ($service -in $SeataServices) { 'true' } else { 'false' }
    if ($seataEnabled -eq 'true' -and $Platform -ne 'boot3') { throw 'Task Seata coordinator requires Boot 3.' }
    $port = $ports[$service]
    $probe = [System.Net.Sockets.TcpClient]::new()
    try { $probe.Connect('127.0.0.1', $port); throw "Port $port is occupied; refusing to replace any process." }
    catch [System.Net.Sockets.SocketException] { }
    finally { $probe.Dispose() }
    $artifact = if ($service -eq 'auth') { 'tj-auth-service' } else { "tj-$service" }
    $jar = Join-Path $repo "$($modules[$service])/target/$artifact.jar"
    if (!(Test-Path -LiteralPath $jar)) { throw "Package the project first: $jar" }
    $args = @('-Xms64m', '-Xmx192m', '-XX:MaxDirectMemorySize=32m', '-XX:ActiveProcessorCount=2',
        "-Duser.home=$runDir", "-Dnacos.logging.path=$runDir/nacos-logs", '-jar', $jar,
        '--spring.profiles.active=upgrade-test', "--server.port=$port", '--server.address=127.0.0.1',
        '--spring.cloud.nacos.server-addr=127.0.0.1:8848',
        "--spring.cloud.nacos.config.namespace=$namespace", "--spring.cloud.nacos.discovery.namespace=$namespace",
        '--spring.cloud.nacos.discovery.ip=127.0.0.1', '--spring.cloud.nacos.discovery.group=DEFAULT_GROUP',
        "--$redisPrefix.host=127.0.0.1", "--$redisPrefix.port=16379",
        '--spring.rabbitmq.virtual-host=tianji-platform-upgrade-test', "--seata.enabled=$seataEnabled",
        '--spring.cloud.sentinel.enabled=false', "--tj.xxl-job.executor.port=$($port+1000)",
        '--tj.xxl-job.admin.address=', '--logging.level.com.tianji=INFO')
    if ($service -ne 'gateway') { $args += "--tj.jdbc.database=tj_upgrade_$service" }
    if ($service -eq 'search') {
        if ($Platform -ne 'boot3') { throw 'Isolated search index requires the Boot 3 implementation.' }
        $args += @('--spring.elasticsearch.uris=http://127.0.0.1:19201', '--tj.search.course-index=tj_upgrade_course_p4')
    }
    # Start-Process joins ArgumentList; quote each argument to preserve paths containing spaces.
    $quoted = $args | ForEach-Object { '"' + $_.Replace('"', '\"') + '"' }
    $previousKey = $env:DEEPSEEK_API_KEY
    try {
        if ($service -eq 'aigc' -and [string]::IsNullOrWhiteSpace($previousKey)) {
            $env:DEEPSEEK_API_KEY = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', 'User')
            if ([string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY)) {
                $env:DEEPSEEK_API_KEY = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', 'Machine')
            }
            if ([string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY)) { throw 'DEEPSEEK_API_KEY is not configured.' }
        }
        $process = Start-Process -FilePath $java -ArgumentList $quoted -WorkingDirectory $repo -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $runDir "$service.stdout.log") `
            -RedirectStandardError (Join-Path $runDir "$service.stderr.log")
    } finally { $env:DEEPSEEK_API_KEY = $previousKey }
    $manifest += [pscustomobject]@{ service = $service; pid = $process.Id; port = $port; started = $process.StartTime.ToUniversalTime().ToString('o'); java = $java; jar = $jar }
    $manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
    Write-Output "Started isolated $service process $($process.Id), loopback port $port"
}
