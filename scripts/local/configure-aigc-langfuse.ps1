param(
    [string]$ConfigPath = (Join-Path $env:USERPROFILE '.tianji/aigc-langfuse.properties'),
    [string]$LegacyStartupScript
)

$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $ConfigPath) {
    throw "Config already exists; edit it directly: $ConfigPath"
}

# 只解析旧脚本的 set 行，不执行脚本、不启动服务、不打印凭据。
$legacy = if ($LegacyStartupScript) { [IO.File]::ReadAllText((Resolve-Path -LiteralPath $LegacyStartupScript)) } else { '' }
function Read-Setting([string]$Name) {
    foreach ($scope in @('Process', 'User', 'Machine')) {
        $value = [Environment]::GetEnvironmentVariable($Name, $scope)
        if (![string]::IsNullOrWhiteSpace($value)) { return $value }
    }
    $match = [regex]::Match($legacy, '(?im)^\s*set\s+"?' + [regex]::Escape($Name) + '=([^\r\n]*)')
    if ($match.Success) { return $match.Groups[1].Value.Trim('"') }
    throw "Missing $Name. Set it in this process or supply -LegacyStartupScript."
}

$settings = [ordered]@{
    'enabled' = 'true'
    'endpoint' = Read-Setting 'LANGFUSE_OTLP_TRACES_ENDPOINT'
    'public-key' = Read-Setting 'LANGFUSE_PUBLIC_KEY'
    'secret-key' = Read-Setting 'LANGFUSE_SECRET_KEY'
}
$variables = @{
    'enabled' = 'TJ_AI_LANGFUSE_ENABLED'
    'endpoint' = 'LANGFUSE_OTLP_TRACES_ENDPOINT'
    'public-key' = 'LANGFUSE_PUBLIC_KEY'
    'secret-key' = 'LANGFUSE_SECRET_KEY'
}
$lines = @('# Local Langfuse configuration. Contains credentials; keep outside the repository.')
foreach ($key in $settings.Keys) {
    $value = $settings[$key].Replace('\', '\\').Replace("`r", '\r').Replace("`n", '\n')
    $lines += 'tj.ai.knowledge.observation.langfuse.' + $key + '=${' + $variables[$key] + ':' + $value + '}'
}
$directory = Split-Path -Parent ([IO.Path]::GetFullPath($ConfigPath))
New-Item -ItemType Directory -Force -Path $directory | Out-Null
[IO.File]::WriteAllLines([IO.Path]::GetFullPath($ConfigPath), $lines, [Text.UTF8Encoding]::new($false))
Write-Output "Local Langfuse configuration saved: $ConfigPath (credentials hidden). Restart AIGC with the local profile to load it."
