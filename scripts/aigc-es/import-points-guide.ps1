param(
    [string]$JavaHome = 'D:/Java/jdk-17',
    [ValidateSet('elasticsearch', 'redis')][string]$StoreType = 'elasticsearch',
    [ValidateSet('points', 'coupons')][string]$Document = 'points'
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$jar = Join-Path $repo 'tj-aigc/target/tj-aigc.jar'
$runDir = Join-Path $repo $(if ($StoreType -eq 'redis') { 'target/aigc-redis8/knowledge' } else { 'target/aigc-es/knowledge' })
if (!(Test-Path -LiteralPath $jar)) { throw 'Build tj-aigc first: mvn -pl tj-aigc -am -DskipTests package' }
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$names = @('DASHSCOPE_API_KEY', 'DASHSCOPE_BASE_URL', 'TJ_AIGC_ES_PASSWORD',
    'TJ_AI_KNOWLEDGE_STORE_TYPE', 'TJ_AIGC_VECTOR_REDIS_PASSWORD')
$saved = @{}
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    foreach ($name in @('DASHSCOPE_API_KEY', 'DASHSCOPE_BASE_URL')) {
        $value = [Environment]::GetEnvironmentVariable($name, 'Process')
        if ([string]::IsNullOrWhiteSpace($value)) { $value = [Environment]::GetEnvironmentVariable($name, 'User') }
        if ([string]::IsNullOrWhiteSpace($value)) { $value = [Environment]::GetEnvironmentVariable($name, 'Machine') }
        if ([string]::IsNullOrWhiteSpace($value)) { throw "Missing environment variable: $name" }
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
    $env:TJ_AI_KNOWLEDGE_STORE_TYPE = $StoreType
    if ($StoreType -eq 'redis') {
        $env:TJ_AIGC_VECTOR_REDIS_PASSWORD = (Get-Content -LiteralPath (Join-Path $repo 'target/aigc-redis8/private/password.txt') -Raw).Trim()
    } else {
        $env:TJ_AIGC_ES_PASSWORD = (Get-Content -LiteralPath (Join-Path $repo 'target/aigc-es/private/application-password.txt') -Raw).Trim()
    }
    $log = Join-Path $runDir "$Document-import.log"
    $report = Join-Path $runDir "$Document-import.json"
    # Native Java stderr (SLF4J etc.) must not trip Stop; only exit code matters.
    $prevErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & (Join-Path $JavaHome 'bin/java.exe') '-Dloader.main=com.tianji.aigc.knowledge.KnowledgeImportCommand' `
        '-cp' $jar 'org.springframework.boot.loader.launch.PropertiesLauncher' $report $Document *> $log
    $importExit = $LASTEXITCODE
    $ErrorActionPreference = $prevErrorAction
    if ($importExit -ne 0) {
        Get-Content -LiteralPath $log -Tail 25 | ForEach-Object {
            $line = $_
            foreach ($secret in @($env:DASHSCOPE_API_KEY, $env:TJ_AIGC_ES_PASSWORD, $env:TJ_AIGC_VECTOR_REDIS_PASSWORD)) {
                if (![string]::IsNullOrWhiteSpace($secret)) { $line = $line.Replace($secret, '[REDACTED]') }
            }
            $line
        }
        throw "Knowledge import failed; see $log"
    }
    Get-Content -LiteralPath $log | Where-Object { $_ -like 'KNOWLEDGE_IMPORT_OK*' }
    Write-Output "Import and retrieval report: $report"
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}
