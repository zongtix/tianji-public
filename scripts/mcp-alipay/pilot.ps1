param(
    [ValidateSet('Start','Check','Stop')][string]$Action='Check',
    [string]$FrontendRoot='D:/Java/tj-protal',
    [string]$JavaHome='D:/Java/jdk-17'
)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
. "$repo/scripts/local/environment-common.ps1"
$runtime=Join-Path $repo 'target/mcp-alipay'
$recordPath=Join-Path $runtime 'processes.json'
$records=if(Test-Path -LiteralPath $recordPath){@(Get-Content $recordPath -Raw | ConvertFrom-Json)}else{@()}
$mutex=[Threading.Mutex]::new($false,'Local\TianjiAlipaySandbox')
$locked=$false
function Get-Status {
    $alive=$records.Count -eq 2 -and @($records | Where-Object {!(Get-RecordedProcess $_ $_.name)}).Count -eq 0
    $trade=$false; $web=$false
    try{$health=Invoke-RestMethod 'http://127.0.0.1:19188/sandbox-checkout/health' -TimeoutSec 3 -Headers @{'user-info'='900000000000000028';'x-request-from'='feign'}; $trade=$health.status -eq 'UP' -and $health.environment -eq 'sandbox'}catch{}
    try{$web=(Invoke-RestMethod 'http://127.0.0.1:18085/mcp-pilot-health' -TimeoutSec 5).checkoutEnabled -eq $true}catch{}
    return @{ready=($alive -and $trade -and $web);ownedProcesses=$alive;trade=$trade;web=$web;
        environment='sandbox';url='http://127.0.0.1:18085/#/assistant/index?pilot=mcp'}
}
function Stop-Owned {
    $reverse=@($records); [array]::Reverse($reverse)
    foreach($record in $reverse){$owned=Get-RecordedProcess $record $record.name; if($owned){
        try{Stop-Process -Id $owned.Id -Force; $owned.WaitForExit(5000) | Out-Null}catch{if(Get-RecordedProcess $record $record.name){throw}}
        Write-Host "Stopped owned sandbox $($record.role) PID $($record.pid)"
    }}
}
try{
    try{$locked=$mutex.WaitOne(0)}catch [Threading.AbandonedMutexException]{$locked=$true}
    if(!$locked){throw 'Another sandbox lifecycle action is running'}
    if($Action -eq 'Stop'){Stop-Owned; Write-Host 'Sandbox trial stopped; course pilot, daily services and order history retained.'; return}
    if($Action -eq 'Check'){$status=Get-Status; $status | ConvertTo-Json; if(!$status.ready){throw 'Sandbox trial is not ready'}; return}
    $alive=@($records | Where-Object {Get-RecordedProcess $_ $_.name})
    if($alive.Count){if($alive.Count -ne 2){throw 'Sandbox trial is partly running; Stop it before Start'}; $status=Get-Status; $status | ConvertTo-Json; if(!$status.ready){throw 'Existing sandbox trial is not ready'}; return}
    & "$repo/scripts/mcp-pilot/pilot.ps1" -Action Check
    foreach($port in @(19188,18085)){if(Test-LocalPort $port){throw "Port $port is already occupied; refusing to replace its owner"}}
    $frontend=(Resolve-Path -LiteralPath $FrontendRoot).Path
    foreach($file in @('node_modules/vite/package.json','src/utils/sandboxCheckout.mjs')){if(!(Test-Path -LiteralPath (Join-Path $frontend $file))){throw "Frontend prerequisite missing: $file"}}
    $entry=Join-Path $repo 'target/alipay-sandbox-preflight/runtime/node_modules/@alipay/mcp-server-alipay/dist/index.js'
    if(!(Test-Path -LiteralPath $entry)){throw 'Install the pinned official MCP package using the README first'}
    $package=Get-Content (Join-Path (Split-Path (Split-Path $entry)) 'package.json') -Raw | ConvertFrom-Json
    if($package.version -ne '2.0.0'){throw 'Expected official MCP version 2.0.0'}
    $java=Join-Path $JavaHome 'bin/java.exe'
    if(!(Test-Path -LiteralPath "$repo/tj-trade/target/tj-trade.jar")){throw 'Build tj-trade first'}
    $node=(Get-Command node).Source
    $courseRun=(Get-Content "$repo/target/mcp-pilot/current.json" -Raw | ConvertFrom-Json).directory
    $coursePort=(Get-Content "$courseRun/sources.json" -Raw | ConvertFrom-Json).coursePort
    New-Item -ItemType Directory -Force $runtime | Out-Null
    $records=@()
    function Start-Owned([string]$Role,[string]$File,[string[]]$Arguments){
        $quoted=$Arguments | ForEach-Object {'"'+$_.Replace('"','\"')+'"'}
        $child=Start-Process -FilePath $File -ArgumentList $quoted -WorkingDirectory $repo -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput "$runtime/$Role.stdout.log" -RedirectStandardError "$runtime/$Role.stderr.log"
        $script:records+=@([pscustomobject]@{role=$Role;pid=$child.Id;started=$child.StartTime.ToUniversalTime().ToString('o');name=$child.ProcessName})
        Write-EnvironmentJson $recordPath $script:records
        Write-Host "Started sandbox $Role PID $($child.Id)"
    }
    $names=@('AP_APP_ID','AP_APP_KEY','AP_PUB_KEY','ALIPAY_MCP_SCRIPT','ALIPAY_MCP_NODE','ALIPAY_MCP_USERS','ALIPAY_MCP_COURSES','NACOS_NAMESPACE')
    $saved=@{}; foreach($name in $names){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
    try{
        foreach($name in @('AP_APP_ID','AP_APP_KEY','AP_PUB_KEY')){
            $value=[Environment]::GetEnvironmentVariable($name,'User')
            if([string]::IsNullOrWhiteSpace($value)){throw "Required user variable missing: $name"}
            [Environment]::SetEnvironmentVariable($name,$value,'Process')
        }
        $env:ALIPAY_MCP_SCRIPT=$entry; $env:ALIPAY_MCP_NODE=$node
        $env:ALIPAY_MCP_USERS='900000000000000028'
        $env:ALIPAY_MCP_COURSES='1589888774267072513,1589905661084430337'
        $env:NACOS_NAMESPACE='tianji-local-boot3'
        Start-Owned 'trade' $java @('-Xms48m','-Xmx320m','-XX:ActiveProcessorCount=2','-Dfile.encoding=UTF-8',
            '-jar',"$repo/tj-trade/target/tj-trade.jar",'--spring.profiles.active=local,alipay-sandbox',
            '--server.address=127.0.0.1','--server.port=19188','--spring.cloud.nacos.server-addr=127.0.0.1:8848',
            '--spring.cloud.nacos.discovery.register-enabled=false','--spring.cloud.sentinel.enabled=false',
            '--spring.autoconfigure.exclude=com.tianji.common.autoconfigure.xxljob.XxlJobConfig',
            '--spring.rabbitmq.dynamic=false','--spring.rabbitmq.listener.simple.auto-startup=false',
            '--spring.rabbitmq.listener.direct.auto-startup=false','--seata.enabled=false',
            "--spring.cloud.openfeign.client.config.course.url=http://127.0.0.1:$coursePort",'--logging.level.com.tianji=INFO',
            '--logging.level.io.modelcontextprotocol=OFF')
    }finally{foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}}
    try{
        Start-Owned 'web' $node @("$PSScriptRoot/web.mjs",$frontend)
        $deadline=(Get-Date).AddSeconds(120)
        do{
            if(@($records | Where-Object {!(Get-RecordedProcess $_ $_.name)}).Count){throw 'Sandbox process exited; inspect the run logs'}
            $status=Get-Status
            if($status.ready){break}
            Start-Sleep -Seconds 1
        }while((Get-Date) -lt $deadline)
        Write-EnvironmentJson "$runtime/check.json" $status
        $status | ConvertTo-Json
        if(!$status.ready){throw 'Sandbox startup timed out'}
    }catch{Stop-Owned; throw}
}finally{if($locked){$mutex.ReleaseMutex()};$mutex.Dispose()}
