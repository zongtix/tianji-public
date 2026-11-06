param(
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [ValidateSet('media', 'message', 'pay')][string[]]$Services = @('media', 'message', 'pay')
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$savedJavaHome = $env:JAVA_HOME
$savedPath = $env:Path
$savedOptIn = $env:TJ_UPGRADE_CONTROLLED_SERVICES
$logDir = Join-Path $repo ('target/platform-upgrade/controlled/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
if (!(Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) { throw 'Invalid JavaHome' }
Push-Location $repo
try {
    $env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
    $env:Path = "$env:JAVA_HOME\bin;" + $savedPath
    $env:TJ_UPGRADE_CONTROLLED_SERVICES = 'true'
    $log = Join-Path $logDir 'tests.log'
    $modules = @{ media = 'tj-media'; message = 'tj-message/tj-message-service'; pay = 'tj-pay/tj-pay-service' }
    $tests = @{ media = 'MediaControlledIntegrationTest'; message = 'MessageControlledIntegrationTest'; pay = 'PayControlledIntegrationTest' }
    $selectedModules = ($Services | ForEach-Object { $modules[$_] }) -join ','
    $selectedTests = ($Services | ForEach-Object { $tests[$_] }) -join ','
    & mvn -B -pl $selectedModules -am "-Dtest=$selectedTests" `
        '-Dsurefire.failIfNoSpecifiedTests=false' test *> $log
    $exitCode = $LASTEXITCODE
    # Do not dump application logs/configuration: imported teaching configuration can contain credentials.
    Select-String -Path $log -Pattern '^\[INFO\] Tests run:|^\[ERROR\] Tests run:|BUILD SUCCESS|Total time:' |
        ForEach-Object { $_.Line }
    if ($exitCode -ne 0) { throw "Controlled service checks failed; inspect credential-safe diagnostics in $log" }
    Write-Output "Controlled third-party boundary evidence: $logDir"
} finally {
    $env:JAVA_HOME = $savedJavaHome
    $env:Path = $savedPath
    $env:TJ_UPGRADE_CONTROLLED_SERVICES = $savedOptIn
    Pop-Location
}
