param(
    [ValidateSet('Start', 'Check', 'Status', 'Stop', 'Watch')][string]$Action = 'Start',
    [ValidateSet('Core', 'Aigc', 'Search', 'All')][string]$Target = 'Aigc',
    [switch]$Background,
    [string]$ConfigPath = (Join-Path $PSScriptRoot 'environment.psd1'),
    [ValidateRange(1, 120)][int]$WaitSeconds = 30
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'environment-common.ps1')
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$config = Import-PowerShellDataFile -LiteralPath $ConfigPath
$runDir = Join-Path $repo $config.RuntimeDirectory
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$recordPath = Join-Path $runDir 'watcher.json'
$stopPath = Join-Path $runDir 'stop'
$logPath = Join-Path $runDir 'watcher.log'
$record = if (Test-Path -LiteralPath $recordPath) { Get-Content -LiteralPath $recordPath -Raw | ConvertFrom-Json } else { $null }
$watcher = Get-RecordedProcess $record 'powershell'
$scriptPath = $PSCommandPath
$powershell = Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'

function Write-WatchLog([string]$Message) {
    Add-Content -LiteralPath $logPath -Value ((Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + ' ' + $Message) -Encoding UTF8
}

function Get-RequiredPorts($Profile) {
    $ports = @($config.CorePorts)
    if ($Target -in @('Search', 'All')) { $ports += 19201 }
    if ($Target -eq 'All') { $ports += @($config.BusinessPorts) }
    if ($Target -in @('Aigc', 'All') -and $Profile) {
        if ($Profile['tj.ai.knowledge.enabled'] -eq 'true') {
            if ($Profile['tj.ai.knowledge.store-type'] -eq 'redis') { $ports += 36379 } else { $ports += 19201 }
        }
        if ($Profile['tj.ai.memory.type'] -eq 'mongodb') { $ports += 27017 }
    }
    return @($ports | Select-Object -Unique)
}

if ($Action -eq 'Watch') {
    $identity = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($runDir)).Replace('/', '_').Replace('=', '')
    $mutex = [Threading.Mutex]::new($false, ('Local\TianjiEnvironment_' + $identity))
    $locked = $false
    $children = @{}
    try {
        try { $locked = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $locked = $true }
        if (!$locked) { return }
        Remove-Item -LiteralPath $stopPath -Force -ErrorAction SilentlyContinue
        Write-EnvironmentJson $recordPath @{ pid = $PID; started = (Get-Process -Id $PID).StartTime.ToUniversalTime().ToString('o') }
        if ((Test-Path -LiteralPath $logPath) -and (Get-Item -LiteralPath $logPath).Length -gt 2097152) {
            Move-Item -LiteralPath $logPath -Destination ($logPath + '.previous') -Force
        }
        Write-WatchLog 'Supervisor started. Existing listeners are retained; only owned SSH children are stopped.'
        $lastState = @{}
        $retryAt = @{}
        $profile = $null
        $refreshProfileAt = [datetime]::MinValue
        while (!(Test-Path -LiteralPath $stopPath)) {
            if ((Get-Date) -ge $refreshProfileAt) {
                try { $profile = Get-AigcProfile $repo } catch { }
                $refreshProfileAt = (Get-Date).AddSeconds(20)
            }
            foreach ($tunnel in $config.Tunnels) {
                $name = $tunnel.Name
                if ($tunnel.When -eq 'redis' -and (!$profile -or $profile['tj.ai.knowledge.enabled'] -ne 'true' -or $profile['tj.ai.knowledge.store-type'] -ne 'redis')) { continue }
                if ($tunnel.When -eq 'mongodb' -and (!$profile -or $profile['tj.ai.memory.type'] -ne 'mongodb')) { continue }
                if (Test-LocalPort $tunnel.LocalPort) {
                    if ($lastState[$name] -ne 'ready') { Write-WatchLog "$name ready on 127.0.0.1:$($tunnel.LocalPort)"; $lastState[$name] = 'ready' }
                    continue
                }
                if ($retryAt[$name] -and (Get-Date) -lt $retryAt[$name]) { continue }
                if ($lastState[$name] -ne 'retry') { Write-WatchLog "$name unavailable; reconnecting"; $lastState[$name] = 'retry' }
                $existing = $children[$name]
                if ($existing) {
                    $owned = Get-RecordedProcess $existing 'ssh'
                    if ($owned) { Stop-Process -InputObject $owned; $owned.WaitForExit(3000) | Out-Null }
                }
                $retryAt[$name] = (Get-Date).AddSeconds(10)
                $mapping = "127.0.0.1:$($tunnel.LocalPort):127.0.0.1:$($tunnel.RemotePort)"
                $arguments = @('-i', $config.SshKey.Replace('%USERPROFILE%', $env:USERPROFILE),
                    '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', '-o', 'ConnectTimeout=8',
                    '-o', 'ExitOnForwardFailure=yes', '-o', 'ServerAliveInterval=10', '-o', 'ServerAliveCountMax=3',
                    '-N', '-L', $mapping, $config.SshHost)
                $quoted = $arguments | ForEach-Object { '"' + $_.Replace('"', '\"') + '"' }
                try {
                    $child = Start-Process ssh -ArgumentList $quoted -WindowStyle Hidden -PassThru `
                        -RedirectStandardError (Join-Path $runDir ($name + '.stderr.log'))
                    $children[$name] = @{ pid = $child.Id; started = $child.StartTime.ToUniversalTime().ToString('o'); mapping = $mapping }
                    Write-EnvironmentJson (Join-Path $runDir 'children.json') $children
                } catch { Write-WatchLog "$name SSH launch failed; see $name.stderr.log" }
            }
            Start-Sleep -Seconds 2
        }
    } finally {
        if ($locked) {
            foreach ($childRecord in $children.Values) {
                $owned = Get-RecordedProcess $childRecord 'ssh'
                if ($owned) { Stop-Process -InputObject $owned -ErrorAction SilentlyContinue }
            }
            Remove-Item -LiteralPath $recordPath,$stopPath -Force -ErrorAction SilentlyContinue
            Write-WatchLog 'Supervisor stopped; externally owned listeners retained.'
            $mutex.ReleaseMutex()
        }
        $mutex.Dispose()
    }
    return
}

if ($Action -eq 'Stop') {
    if (!$watcher) { Write-Output 'No recorded environment supervisor is running.'; return }
    New-Item -ItemType File -Path $stopPath -Force | Out-Null
    if (!$watcher.WaitForExit(15000)) { throw 'Supervisor is still stopping; inspect watcher.log. No unrelated processes were stopped.' }
    Write-Output 'Stopped environment supervisor and its own tunnels. Existing business tunnels were retained.'
    return
}

if ($Action -eq 'Start') {
    if (!$watcher) {
        $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $scriptPath, '-Action', 'Watch', '-ConfigPath', (Resolve-Path -LiteralPath $ConfigPath).Path)
        $quoted = $arguments | ForEach-Object { '"' + $_.Replace('"', '\"') + '"' }
        $launchLog = Join-Path $runDir ("launch-$PID.stderr.log")
        $null = Start-Process $powershell -ArgumentList $quoted -WorkingDirectory $repo -WindowStyle Hidden -PassThru `
            -RedirectStandardError $launchLog
        # Wait for the singleton worker to register itself, including a racing Start call.
        for ($attempt = 0; $attempt -lt 30; $attempt++) {
            Start-Sleep -Milliseconds 200
            if (Test-Path -LiteralPath $recordPath) {
                $record = Get-Content -LiteralPath $recordPath -Raw | ConvertFrom-Json
                $watcher = Get-RecordedProcess $record 'powershell'
                if ($watcher) { break }
            }
        }
        if (!$watcher) { throw "Environment supervisor failed to start. Inspect $launchLog and $logPath" }
    }
    Write-Output "Environment supervisor running: PID $($watcher.Id)."
    if ($Background) { return }
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    do {
        if (@($config.CorePorts | Where-Object { !(Test-LocalPort $_) }).Count -eq 0) { break }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
}

if ($Action -eq 'Status') {
    Write-Output ('Supervisor: ' + $(if ($watcher) { "running (PID $($watcher.Id))" } else { 'stopped' }))
    $statusProfile = $null
    try { $statusProfile = Get-AigcProfile $repo } catch { }
    foreach ($port in @($config.CorePorts + $config.BusinessPorts + @($config.Tunnels | ForEach-Object { $_.LocalPort })) | Select-Object -Unique) {
        $tunnel = @($config.Tunnels | Where-Object { $_.LocalPort -eq $port }) | Select-Object -First 1
        if ($statusProfile -and (($tunnel.When -eq 'redis' -and ($statusProfile['tj.ai.knowledge.enabled'] -ne 'true' -or $statusProfile['tj.ai.knowledge.store-type'] -ne 'redis')) -or
                ($tunnel.When -eq 'mongodb' -and $statusProfile['tj.ai.memory.type'] -ne 'mongodb'))) {
            Write-Output "${port}: optional, not selected by current local/Nacos configuration"
            continue
        }
        Write-Output ("{0}: {1}" -f $port, $(if (Test-LocalPort $port) { 'listening' } else { 'unavailable' }))
    }
    Write-Output "Log: $logPath"
    return
}

$failures = @()
foreach ($port in $config.CorePorts) {
    if (!(Test-LocalPort $port)) { $failures += "Core dependency 127.0.0.1:$port unavailable. Check D:\Java\tunnel.log / tunnel-redis.log (existing auto-reconnecting business tunnels)." }
}
if ($failures.Count) { throw ($failures -join "`n") }
$null = Get-NacosText 'shared-spring.yaml'
$database = Get-NacosText 'shared-mybatis.yaml'
if (!(Get-YamlScalar $database 'spring.datasource.driver-class-name') -or (Get-YamlScalar $database 'spring.datasource.url') -notmatch '^jdbc:mysql:') {
    throw 'Nacos shared-mybatis.yaml is missing the MySQL datasource driver/url. Check the namespace before starting Java.'
}
$redis = Get-NacosText 'shared-redis.yaml'
$redisPassword = Resolve-EnvironmentValue (Get-YamlScalar $redis 'spring.data.redis.password')
if (!(Test-RedisAuthentication 6379 $redisPassword)) { throw 'Business Redis authentication/PING failed. Check shared-redis.yaml and its tunnel.' }
Write-Output 'OK Nacos shared configuration, datasource fields and business Redis authentication/PING.'
$profile = $null
if ($Target -in @('Aigc', 'All')) {
    $profile = Get-AigcProfile $repo
    Write-Output "AIGC local/Nacos profile: knowledge=$($profile['tj.ai.knowledge.enabled']), store=$($profile['tj.ai.knowledge.store-type']), memory=$($profile['tj.ai.memory.type'])."
    Assert-ConfiguredSecret 'DEEPSEEK_API_KEY'
    if ($profile['tj.ai.knowledge.enabled'] -eq 'true') {
        Assert-ConfiguredSecret 'DASHSCOPE_API_KEY'
        Assert-ConfiguredSecret 'DASHSCOPE_BASE_URL'
    }
    $workspacePath = Join-Path $repo '.idea/workspace.xml'
    if (Test-Path -LiteralPath $workspacePath) {
        [xml]$workspace = [IO.File]::ReadAllText($workspacePath)
        $vm = $workspace.SelectSingleNode('//configuration[@name="AIGCApplication"]/option[@name="VM_PARAMETERS"]')
        if ($vm -and $vm.value -match '-Dspring.config.additional-location=("[^"]+"|\S+)') {
            foreach ($location in ($matches[1].Trim('"') -split ',')) {
                if ($location.StartsWith('optional:')) { continue }
                if ($location.StartsWith('file:')) {
                    $path = $location.Substring(5).Replace('$PROJECT_DIR$', $repo)
                    if (!(Test-Path -LiteralPath $path)) { throw "Required IDEA config file is missing: $path. Restore the private config before starting Java." }
                }
            }
        }
    }
}
$required = Get-RequiredPorts $profile
if ($Action -eq 'Start') {
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    do {
        $missing = @($required | Where-Object { !(Test-LocalPort $_) })
        if (!$missing.Count) { break }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
}
foreach ($port in $required) {
    if (Test-LocalPort $port) { Write-Output "OK TCP 127.0.0.1:$port" }
    else { $failures += "Dependency 127.0.0.1:$port unavailable. Inspect $logPath and SSH stderr logs." }
}
if ($Target -in @('Aigc', 'All') -and $profile['tj.ai.knowledge.enabled'] -eq 'true' -and $profile['tj.ai.knowledge.store-type'] -eq 'elasticsearch' -and (Test-LocalPort 19201)) {
    $uri = Resolve-EnvironmentValue $profile['spring.elasticsearch.uris']
    $username = Resolve-EnvironmentValue $profile['spring.elasticsearch.username']
    $password = Resolve-EnvironmentValue $profile['spring.elasticsearch.password']
    $basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($username + ':' + $password))
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $uri -Headers @{ Authorization = "Basic $basic" } -TimeoutSec 5
        if ($response.StatusCode -ne 200) { throw 'Unexpected HTTP status' }
        Write-Output 'OK Elasticsearch HTTP authentication (no model requests sent).'
    } catch { $failures += 'Elasticsearch HTTP/authentication failed. Check the configured endpoint and credentials; TCP listening alone is insufficient.' }
}
if ($failures.Count) { throw ($failures -join "`n") }
Write-Output "PASS $Target environment preflight. Custom IDEA JVM/property overrides still require application startup verification."
