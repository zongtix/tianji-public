param([string]$JavaHome = 'D:/Java/jdk-17', [switch]$LiveRag, [switch]$Elasticsearch)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$names = @('JAVA_HOME', 'Path', 'TJ_AIGC_TEST_VECTOR_REDIS_PORT', 'TJ_AIGC_VECTOR_REDIS_PASSWORD',
    'TJ_AIGC_TEST_LIVE_RAG', 'DASHSCOPE_API_KEY', 'DASHSCOPE_BASE_URL', 'DEEPSEEK_API_KEY',
    'TJ_AIGC_TEST_ES_PORT', 'TJ_AIGC_ES_PASSWORD', 'TJ_AIGC_TEST_DASHSCOPE')
$saved = @{}
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
Push-Location $repo
try {
    $env:JAVA_HOME = $JavaHome
    $env:Path = "$JavaHome/bin;$env:Path"
    $env:TJ_AIGC_TEST_VECTOR_REDIS_PORT = '36379'
    $env:TJ_AIGC_VECTOR_REDIS_PASSWORD = (Get-Content target/aigc-redis8/private/password.txt -Raw).Trim()
    $env:TJ_AIGC_TEST_LIVE_RAG = 'false'
    $env:TJ_AIGC_TEST_DASHSCOPE = 'false'
    if ($LiveRag) {
        foreach ($name in @('DASHSCOPE_API_KEY', 'DASHSCOPE_BASE_URL', 'DEEPSEEK_API_KEY')) {
            $value = [Environment]::GetEnvironmentVariable($name, 'Process')
            if ([string]::IsNullOrWhiteSpace($value)) { $value = [Environment]::GetEnvironmentVariable($name, 'User') }
            if ([string]::IsNullOrWhiteSpace($value)) { $value = [Environment]::GetEnvironmentVariable($name, 'Machine') }
            if ([string]::IsNullOrWhiteSpace($value)) { throw "Missing environment variable: $name" }
            [Environment]::SetEnvironmentVariable($name, $value, 'Process')
        }
        $env:TJ_AIGC_TEST_LIVE_RAG = 'true'
    }
    $tests = 'KnowledgeStoreConfigTest,KnowledgeDocumentServiceTest,RedisKnowledgeStoreIntegrationTest,KnowledgeChatClientTest,KnowledgeChatRoutingTest,ChatStreamingContractTest'
    if ($Elasticsearch) {
        $env:TJ_AIGC_TEST_ES_PORT = '19201'
        $env:TJ_AIGC_ES_PASSWORD = (Get-Content target/aigc-es/private/application-password.txt -Raw).Trim()
        $tests += ',KnowledgeStoreIntegrationTest'
    }
    New-Item -ItemType Directory -Force target/aigc-redis8/integration | Out-Null
    $log = 'target/aigc-redis8/integration/tests-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log'
    & mvn -s (Join-Path $env:USERPROFILE '.m2/settings.xml') -pl tj-aigc -am "-Dtest=$tests" '-Dsurefire.failIfNoSpecifiedTests=false' test *> $log
    $code = $LASTEXITCODE
    if ($code -ne 0) {
        Get-Content $log -Tail 45 | ForEach-Object {
            $line = $_
            foreach ($name in @('DASHSCOPE_API_KEY', 'DEEPSEEK_API_KEY', 'TJ_AIGC_VECTOR_REDIS_PASSWORD', 'TJ_AIGC_ES_PASSWORD')) {
                $secret = [Environment]::GetEnvironmentVariable($name, 'Process')
                if (![string]::IsNullOrWhiteSpace($secret)) { $line = $line.Replace($secret, '[REDACTED]') }
            }
            $line
        }
        throw "Tests failed; see $log"
    }
    Select-String -LiteralPath $log -Pattern 'Tests run:|BUILD SUCCESS' | ForEach-Object { $_.Line }
    Write-Output "Test log: $log"
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
    Pop-Location
}
