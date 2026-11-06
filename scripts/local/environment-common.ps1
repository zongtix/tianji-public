$ErrorActionPreference = 'Stop'

# A Windows PowerShell child launched by PowerShell 7 can inherit only Core module
# paths. Restore its own built-in module directory before command auto-loading.
if ($PSVersionTable.PSEdition -eq 'Desktop') {
    $env:PSModulePath = (Join-Path $PSHOME 'Modules') + ';' + $env:PSModulePath
}

function Test-LocalPort([int]$Port) {
    $client = [Net.Sockets.TcpClient]::new()
    try { return $client.ConnectAsync('127.0.0.1', $Port).Wait(400) -and $client.Connected }
    catch { return $false }
    finally { $client.Dispose() }
}

function Get-RecordedProcess($Record, [string]$Name) {
    if (!$Record) { return $null }
    try {
        $process = Get-Process -Id $Record.pid -ErrorAction SilentlyContinue
        if ($process -and $process.ProcessName -eq $Name -and
            $process.StartTime.ToUniversalTime().Ticks -eq ([datetime]$Record.started).ToUniversalTime().Ticks) {
            return $process
        }
    } catch { return $null }
    return $null
}

function Write-EnvironmentJson([string]$Path, $Value) {
    $temporary = $Path + '.' + $PID + '.tmp'
    [IO.File]::WriteAllText($temporary, ($Value | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporary -Destination $Path -Force
}

# Only reads scalar mapping properties needed by this preflight; never evaluates YAML.
# Unsupported YAML constructs fail explicitly instead of guessing configuration.
function Get-YamlScalar([string]$Text, [string]$Path) {
    $stack = @()
    foreach ($line in ($Text -split '\r?\n')) {
        if ($line -notmatch '^( *)([\w-]+):(?:\s+(.*))?\s*$') { continue }
        $indent = $matches[1].Length
        $key = $matches[2]
        $value = $matches[3]
        $stack = @($stack | Where-Object { $_.indent -lt $indent })
        $stack += @{ indent = $indent; key = $key }
        if ((($stack | ForEach-Object { $_.key }) -join '.') -eq $Path) {
            if (!$value -or $value -match '^[>|&*{\[]') { throw "Unsupported scalar configuration: $Path" }
            return $value.Trim().Trim('"', "'")
        }
    }
    return $null
}

function Resolve-EnvironmentValue([string]$Value) {
    for ($iteration = 0; $iteration -lt 10 -and $Value -match '\$\{'; $iteration++) {
        $before = $Value
        $Value = [regex]::Replace($Value, '\$\{([^{}:]+)(?::([^{}]*))?\}', {
            param($match)
            $actual = [Environment]::GetEnvironmentVariable($match.Groups[1].Value, 'Process')
            if ($null -ne $actual) { return $actual }
            if ($match.Groups[2].Success) { return $match.Groups[2].Value }
            throw "Missing process environment variable: $($match.Groups[1].Value). Restart IDEA after setting it."
        })
        if ($Value -ceq $before) { break }
    }
    if ($Value -match '\$\{') { throw 'Cannot resolve configuration placeholder; check environment variables.' }
    return $Value
}

function Get-NacosText([string]$DataId) {
    $namespace = if ($env:NACOS_NAMESPACE) { $env:NACOS_NAMESPACE } else { 'tianji-local-boot3' }
    $server = if ($env:NACOS_SERVER_ADDR) { $env:NACOS_SERVER_ADDR } else { '127.0.0.1:8848' }
    if ($server -notmatch '^https?://') { $server = 'http://' + $server }
    $url = $server.TrimEnd('/') + '/nacos/v1/cs/configs?group=DEFAULT_GROUP&tenant=' +
        [uri]::EscapeDataString($namespace) + '&dataId=' + [uri]::EscapeDataString($DataId)
    try { $response = Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 4 }
    catch { throw "Cannot read Nacos $namespace/DEFAULT_GROUP/$DataId; check its tunnel, namespace and access." }
    if ([string]::IsNullOrWhiteSpace($response.Content)) { throw "Nacos configuration is empty: $namespace/$DataId" }
    return [string]$response.Content
}

function Get-AigcProfile([string]$Repo) {
    $local = [IO.File]::ReadAllText((Join-Path $Repo 'tj-aigc/src/main/resources/application.yml'))
    $remote = Get-NacosText 'aigc-service.yaml'
    $values = @{}
    foreach ($path in @('tj.ai.knowledge.enabled', 'tj.ai.knowledge.store-type', 'tj.ai.memory.type',
            'spring.elasticsearch.uris', 'spring.elasticsearch.username', 'spring.elasticsearch.password',
            'tj.ai.knowledge.redis.port')) {
        $value = Get-YamlScalar $remote $path
        if ($null -eq $value) { $value = Get-YamlScalar $local $path }
        # Resolve only the non-secret switches here. Credentials are resolved only by the relevant check.
        if ($path -in @('tj.ai.knowledge.enabled', 'tj.ai.knowledge.store-type', 'tj.ai.memory.type')) {
            $direct = [Environment]::GetEnvironmentVariable(($path.Replace('.', '_').Replace('-', '').ToUpperInvariant()), 'Process')
            if ($null -ne $direct) { $value = $direct }
            $value = Resolve-EnvironmentValue $value
        }
        $values[$path] = $value
    }
    if ($values['tj.ai.knowledge.store-type'] -notin @('elasticsearch', 'redis')) { throw 'Invalid knowledge store-type in local/Nacos configuration.' }
    if ($values['tj.ai.memory.type'] -notin @('mysql', 'mongodb')) { throw 'Invalid memory type in local/Nacos configuration.' }
    return $values
}

function Assert-ConfiguredSecret([string]$Name) {
    if (![string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($Name, 'Process'))) { return }
    foreach ($scope in @('User', 'Machine')) {
        if (![string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($Name, $scope))) {
            Write-Output "WARN $Name exists at $scope scope; the current shell has not inherited it. Restart IDEA if it was set after IDEA opened."
            return
        }
    }
    throw "Missing $Name. Configure it outside the repository and restart IDEA."
}

function Test-RedisAuthentication([int]$Port, [string]$Password) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        if (!$client.ConnectAsync('127.0.0.1', $Port).Wait(1000)) { return $false }
        $stream = $client.GetStream()
        $stream.ReadTimeout = 2000; $stream.WriteTimeout = 2000
        $commands = @()
        if ($Password) { $commands += ,@('AUTH', $Password) }
        $commands += ,@('PING')
        foreach ($command in $commands) {
            $request = '*' + $command.Count + "`r`n"
            foreach ($argument in $command) { $request += '$' + [Text.Encoding]::UTF8.GetByteCount($argument) + "`r`n" + $argument + "`r`n" }
            $bytes = [Text.Encoding]::UTF8.GetBytes($request)
            $stream.Write($bytes, 0, $bytes.Length)
            $response = [Text.StringBuilder]::new()
            do {
                $next = $stream.ReadByte()
                if ($next -lt 0) { return $false }
                $null = $response.Append([char]$next)
            } while ($next -ne 10 -and $response.Length -lt 1024)
            if ($response.ToString() -notin @("+OK`r`n", "+PONG`r`n")) { return $false }
        }
        return $true
    } catch { return $false }
    finally { $client.Dispose() }
}
