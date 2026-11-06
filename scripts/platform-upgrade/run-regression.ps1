param(
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [switch]$RedisIntegration,
    [switch]$SearchIntegration,
    [switch]$Rebuild
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$savedJavaHome = $env:JAVA_HOME
$savedPath = $env:Path
$savedRedisPort = $env:TJ_REMARK_TEST_REDIS_PORT
$savedEsPort = $env:TJ_SEARCH_TEST_ES_PORT
$savedEsUsername = $env:TJ_SEARCH_TEST_ES_USERNAME
$savedEsPassword = $env:TJ_SEARCH_TEST_ES_PASSWORD
$logDir = Join-Path $repo ('target/platform-upgrade/regression/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
if (!(Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) { throw 'JavaHome must contain bin/java.exe' }
$tests = @(Get-Content (Join-Path $PSScriptRoot 'regression-tests.txt') | Where-Object { $_.Trim() })
if (@($tests | Where-Object { $_ -notmatch '^[A-Za-z0-9_.]+Test$' }).Count) { throw 'Invalid regression whitelist entry' }
$selector = ($tests | ForEach-Object { ($_ -split '\.')[-1] }) -join ','
function Invoke-CheckedMaven([string]$Name, [string[]]$MavenArgs) {
    $log = Join-Path $logDir "$Name.log"
    & mvn @MavenArgs *> $log
    if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath $log -Tail 35; throw "Maven $Name failed; see $log" }
    Select-String -Path $log -Pattern '^\[INFO\] Tests run:|BUILD SUCCESS|Total time:' | ForEach-Object { $_.Line }
}
Push-Location $repo
try {
    if ($Rebuild) {
        # Recompile after JDK/dependency ABI changes without deleting task fixtures/evidence.
        foreach ($pom in @(git ls-files -- '*pom.xml' | Where-Object { $_ -like 'tj-*' })) {
            foreach ($output in @('classes', 'test-classes')) {
                $candidate = Join-Path (Split-Path (Join-Path $repo $pom)) "target/$output"
                if (!(Test-Path -LiteralPath $candidate)) { continue }
                $resolved = (Resolve-Path -LiteralPath $candidate).Path
                if (!$resolved.StartsWith($repo + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
                    !(($resolved -replace '\\', '/') -match '/target/(classes|test-classes)$')) {
                    throw "Refusing to remove unexpected build output: $resolved"
                }
                Remove-Item -LiteralPath $resolved -Recurse -Force
            }
        }
    }
    $env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
    $env:Path = "$env:JAVA_HOME\bin;" + $savedPath
    Remove-Item Env:TJ_REMARK_TEST_REDIS_PORT -ErrorAction SilentlyContinue
    Remove-Item Env:TJ_SEARCH_TEST_ES_PORT -ErrorAction SilentlyContinue
    Invoke-CheckedMaven 'compile' @('-B', '-DskipTests', 'test-compile')
    Invoke-CheckedMaven 'tests' @('-B', '-pl', 'tj-api,tj-gateway,tj-learning,tj-remark,tj-promotion,tj-search,tj-aigc', '-am',
        "-Dtest=$selector", '-Dsurefire.failIfNoSpecifiedTests=false', 'test')
    if ($RedisIntegration) {
        # Separate physical Redis instance. It intentionally FLUSHDBs only after its marker check.
        $env:TJ_REMARK_TEST_REDIS_PORT = '26379'
        Invoke-CheckedMaven 'redis-tests' @('-B', '-pl', 'tj-remark', '-am', '-Dtest=LikeRedisIntegrationTest',
            '-Dsurefire.failIfNoSpecifiedTests=false', 'test')
    }
    if ($SearchIntegration) {
        # Tests create UUID-suffixed task indexes and remove only indexes created by that test.
        $env:TJ_SEARCH_TEST_ES_PORT = '19201'
        $env:TJ_SEARCH_TEST_ES_USERNAME = 'tj_search_app'
        $env:TJ_SEARCH_TEST_ES_PASSWORD = (Get-Content -LiteralPath (Join-Path $repo 'target/aigc-es/private/search-password.txt') -Raw).Trim()
        Invoke-CheckedMaven 'search-tests' @('-B', '-pl', 'tj-search', '-am', '-Dtest=CourseSearchIntegrationTest',
            '-Dsurefire.failIfNoSpecifiedTests=false', 'test')
    }
    Write-Output "Regression evidence: $logDir"
} finally {
    $env:JAVA_HOME = $savedJavaHome
    $env:Path = $savedPath
    $env:TJ_REMARK_TEST_REDIS_PORT = $savedRedisPort
    $env:TJ_SEARCH_TEST_ES_PORT = $savedEsPort
    $env:TJ_SEARCH_TEST_ES_USERNAME = $savedEsUsername
    $env:TJ_SEARCH_TEST_ES_PASSWORD = $savedEsPassword
    Pop-Location
}
