param(
    [string]$JavaHome = 'D:/Java/jdk-17',
    [switch]$WithInfrastructure
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'environment-common.ps1')
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$names = @('JAVA_HOME', 'Path', 'TJ_AIGC_TEST_COURSE_SYNC', 'TJ_AIGC_ES_PASSWORD',
    'TJ_SYNC_TEST_MQ_USERNAME', 'TJ_SYNC_TEST_MQ_PASSWORD', 'TJ_SYNC_TEST_MQ_VHOST')
$saved = @{}
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$log = Join-Path $repo 'target/course-sync-verification.log'
New-Item -ItemType Directory -Force -Path (Split-Path $log) | Out-Null
Push-Location $repo
try {
    $env:JAVA_HOME = $JavaHome
    $env:Path = "$JavaHome/bin;" + $env:Path
    $env:TJ_AIGC_TEST_COURSE_SYNC = 'false'
    if ($WithInfrastructure) {
        $profile = Get-AigcProfile $repo
        $env:TJ_AIGC_ES_PASSWORD = Resolve-EnvironmentValue $profile['spring.elasticsearch.password']
        $mq = Get-NacosText 'shared-mq.yaml'
        $env:TJ_SYNC_TEST_MQ_USERNAME = Resolve-EnvironmentValue (Get-YamlScalar $mq 'spring.rabbitmq.username')
        $env:TJ_SYNC_TEST_MQ_PASSWORD = Resolve-EnvironmentValue (Get-YamlScalar $mq 'spring.rabbitmq.password')
        $env:TJ_SYNC_TEST_MQ_VHOST = Resolve-EnvironmentValue (Get-YamlScalar $mq 'spring.rabbitmq.virtual-host')
        $env:TJ_AIGC_TEST_COURSE_SYNC = 'true'
    }
    $tests = 'CourseIndexSyncServiceTest,CourseIndexSyncTaskTest,CourseIndexSyncConfigTest,' +
        'CourseDocumentServiceTest,CourseSemanticIndexHttpTest,CourseIndexSnapshotTest,' +
        'CourseBaseInfoTest,CourseEventPublisherTest,CourseRetrievalConfigTest,CourseSemanticToolsTest'
    if ($WithInfrastructure) { $tests += ',CourseIndexSyncIntegrationTest' }
    & mvn -s "$env:USERPROFILE/.m2/settings.xml" -o -pl tj-aigc,tj-course -am "-Dtest=$tests" `
        '-Dsurefire.failIfNoSpecifiedTests=false' test *> $log
    $testExit = $LASTEXITCODE
    Get-Content -LiteralPath $log | Where-Object { $_ -match 'Tests run:|BUILD SUCCESS|BUILD FAILURE' }
    if ($testExit -ne 0) { throw "Course index verification failed. Inspect $log" }
    Write-Output "Course index verification passed. Log: $log"
} finally {
    Pop-Location
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}
