param([string]$JavaHome = 'D:\Java\jdk-17', [switch]$Integration)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$savedJava = $env:JAVA_HOME
$savedPath = $env:Path
$savedMongo = $env:TJ_AIGC_TEST_MONGODB_URI
$savedUrl = $env:TJ_AIGC_TEST_MYSQL_URL
$savedUser = $env:TJ_AIGC_TEST_MYSQL_USER
$savedPassword = $env:TJ_AIGC_TEST_MYSQL_PASSWORD
Push-Location $repo
try {
    $env:JAVA_HOME = $JavaHome
    $env:Path = "$JavaHome\bin;$env:Path"
    $tests = 'MysqlChatMemoryRepositoryTest,ChatMemoryStorageConfigTest,RedisChatMemoryRepositoryTest,ChatStopMemoryTest,ChatStreamingContractTest'
    if ($Integration) {
        $env:TJ_AIGC_TEST_MONGODB_URI = (Get-Content -LiteralPath 'target/aigc-memory/private/mongodb-uri.txt' -Raw).Trim()
        $mysql = Get-Content -LiteralPath 'target/aigc-memory/private/mysql.json' -Raw | ConvertFrom-Json
        $env:TJ_AIGC_TEST_MYSQL_URL = $mysql.url
        $env:TJ_AIGC_TEST_MYSQL_USER = $mysql.username
        $env:TJ_AIGC_TEST_MYSQL_PASSWORD = $mysql.password
        $tests += ',MysqlChatMemoryIntegrationTest,MongoChatMemoryIntegrationTest'
    }
    New-Item -ItemType Directory -Force -Path 'target/aigc-memory' | Out-Null
    $log = 'target/aigc-memory/tests-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log'
    & mvn -s (Join-Path $env:USERPROFILE '.m2/settings.xml') -pl tj-aigc -am "-Dtest=$tests" '-Dsurefire.failIfNoSpecifiedTests=false' test *> $log
    if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath $log -Tail 40; throw "Tests failed; see $log" }
    Select-String -LiteralPath $log -Pattern 'Tests run:|BUILD SUCCESS' | ForEach-Object { $_.Line }
    Write-Output "Test log: $log"
}
finally {
    $env:JAVA_HOME = $savedJava
    $env:Path = $savedPath
    $env:TJ_AIGC_TEST_MONGODB_URI = $savedMongo
    $env:TJ_AIGC_TEST_MYSQL_URL = $savedUrl
    $env:TJ_AIGC_TEST_MYSQL_USER = $savedUser
    $env:TJ_AIGC_TEST_MYSQL_PASSWORD = $savedPassword
    Pop-Location
}
