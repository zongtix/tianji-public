param([string]$Label='current',[string]$Tests='ChatRedisCoordinationTest')
$ErrorActionPreference='Stop'
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$run=Join-Path $repo 'target/mcp-evaluation'
. "$repo/scripts/local/environment-common.ps1"
if($Label -notmatch '^[a-z0-9-]+$' -or $Tests -notmatch '^[A-Za-z0-9#+,]+$'){throw 'Invalid test selector or label'}
if(Test-LocalPort 26426){throw 'Redis acceptance port is already occupied'}
$sshArguments=@('-i',"$env:USERPROFILE/.ssh/id_ed25519",'-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=8')
$remote='developer@tianji-dev.example.invalid'
$container=$null
$tunnel=$null
$savedPort=$env:TJ_AIGC_TEST_REDIS_PORT
$savedJava=$env:JAVA_HOME
$savedPath=$env:Path
try{
    # This exact image was read from the existing business Redis: Redis 6.2.6.
    $created=& ssh @sshArguments $remote 'docker run -d --rm --name tj-aigc-mcp-redis-it-20261026 --memory 96m --cpus 0.5 -p 127.0.0.1:26426:6379 64831312291f redis-server --appendonly no --save ""'
    if($LASTEXITCODE -ne 0){throw 'Cannot create isolated matching-version Redis'}
    $container=([string]$created).Trim()
    if($container -notmatch '^[a-f0-9]{64}$'){throw 'Unexpected container identity'}
    & ssh @sshArguments $remote "docker exec $container redis-cli SET codex:aigc:test-instance aigc-coordination-test" | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Cannot mark isolated Redis'}
    $arguments=$sshArguments+@('-o','ExitOnForwardFailure=yes','-N','-L','127.0.0.1:26426:127.0.0.1:26426',$remote)
    $quoted=$arguments | ForEach-Object {'"'+$_.Replace('"','\"')+'"'}
    $tunnel=Start-Process ssh -ArgumentList $quoted -WindowStyle Hidden -PassThru -RedirectStandardError "$run/redis-$Label-tunnel.log"
    $tunnelStarted=$tunnel.StartTime
    for($attempt=0;$attempt -lt 30 -and !(Test-LocalPort 26426);$attempt++){Start-Sleep -Milliseconds 200}
    if(!(Test-LocalPort 26426)){throw 'Isolated Redis tunnel is unavailable'}
    $env:TJ_AIGC_TEST_REDIS_PORT='26426'
    $env:JAVA_HOME='D:\Java\jdk-17'; $env:Path="$env:JAVA_HOME/bin;$savedPath"
    Push-Location $repo
    try{
        & mvn -B -pl tj-aigc -am "-Dtest=$Tests" '-Dsurefire.failIfNoSpecifiedTests=false' test *> "$run/redis-$Label.log"
        $testCode=$LASTEXITCODE
    }finally{Pop-Location}
    Select-String -LiteralPath "$run/redis-$Label.log" -Pattern 'Tests run:|^\[ERROR\]   ChatRedis|BUILD SUCCESS|BUILD FAILURE' | ForEach-Object {$_.Line}
    if($testCode -ne 0){throw "Redis acceptance failed; see $run/redis-$Label.log"}
}finally{
    $env:TJ_AIGC_TEST_REDIS_PORT=$savedPort; $env:JAVA_HOME=$savedJava; $env:Path=$savedPath
    if($tunnel){
        $owned=Get-Process -Id $tunnel.Id -ErrorAction SilentlyContinue
        if($owned -and $owned.ProcessName -eq 'ssh' -and $owned.StartTime -eq $tunnelStarted){Stop-Process -InputObject $owned}
    }
    if($container -match '^[a-f0-9]{64}$'){& ssh @sshArguments $remote "docker stop $container" | Out-Null}
}
