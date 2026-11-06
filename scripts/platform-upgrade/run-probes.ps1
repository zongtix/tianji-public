param(
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [Parameter(Mandatory = $true)][string]$Python,
    [Parameter(Mandatory = $true)][string]$SshHost,
    [Parameter(Mandatory = $true)][string]$SshKey,
    [switch]$BusinessTransactions
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$savedJavaHome = $env:JAVA_HOME
$savedPath = $env:Path
$savedPassword = $env:TJ_UPGRADE_TEST_PASSWORD
$savedExam = $env:TJ_UPGRADE_TEST_EXAM
$logDir = Join-Path $repo ('target/platform-upgrade/probes/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
function Invoke-ProbeMaven([string]$Name, [string[]]$MavenArgs) {
    $log = Join-Path $logDir "$Name.log"
    & mvn @MavenArgs *> $log
    if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath $log -Tail 25; throw "$Name failed; see $log" }
    Select-String -Path $log -Pattern 'Tests run:|^PASS|PROBE PASS|BUILD SUCCESS' | ForEach-Object { $_.Line }
}
Push-Location $repo
try {
    $env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
    $env:Path = "$env:JAVA_HOME\bin;" + $savedPath
    $schemaArgs = @('--ssh-host', $SshHost, '--ssh-key', $SshKey)
    if ($BusinessTransactions) { $schemaArgs += '--exam' }
    & $Python (Join-Path $PSScriptRoot 'prepare-probe.py') @schemaArgs
    if ($LASTEXITCODE -ne 0) { throw 'Test schema preparation failed' }
    $env:TJ_UPGRADE_TEST_PASSWORD = ((& ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=8 $SshHost 'cat /root/tianji-platform-upgrade-test/mysql-password') | Out-String).Trim()
    if ($env:TJ_UPGRADE_TEST_PASSWORD -notmatch '^[a-f0-9]{48}$') { throw 'Invalid test credential' }
    $env:TJ_UPGRADE_TEST_EXAM = if ($BusinessTransactions) { 'true' } else { $null }
    Invoke-ProbeMaven 'nacos-client' @('-B', '-f', 'scripts/platform-upgrade/probes/nacos-client/pom.xml', 'compile', 'exec:exec')
    Invoke-ProbeMaven 'transition' @('-B', '-f', 'scripts/platform-upgrade/probes/transition/pom.xml', 'test')
    Invoke-ProbeMaven 'platform' @('-B', '-f', 'scripts/platform-upgrade/probes/platform/pom.xml', 'test')
    Invoke-ProbeMaven 'effective-pom' @('-B', '-f', 'scripts/platform-upgrade/probes/platform/pom.xml', 'help:effective-pom', '-Doutput=target/effective-pom.xml')
    Invoke-ProbeMaven 'dependency-tree' @('-B', '-f', 'scripts/platform-upgrade/probes/platform/pom.xml', 'dependency:tree', '-DoutputFile=target/dependency-tree.txt')
    Write-Output "Probe evidence: $logDir"
} finally {
    $env:JAVA_HOME = $savedJavaHome
    $env:Path = $savedPath
    $env:TJ_UPGRADE_TEST_PASSWORD = $savedPassword
    $env:TJ_UPGRADE_TEST_EXAM = $savedExam
    Pop-Location
}
