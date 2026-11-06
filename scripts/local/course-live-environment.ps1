param([ValidateSet('Start','Stop')][string]$Action='Start', [switch]$RecoverUnspentStartup,
    [string]$RunDirectory='target/course-workflow/approved-seven-20261027', [switch]$CaptureEffectiveConfig,
    [ValidateRange(1,80)][int]$ProviderRequestLimit=80)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$run=[IO.Path]::GetFullPath((Join-Path $repo $RunDirectory))
$allowedRoot=[IO.Path]::GetFullPath((Join-Path $repo 'target/course-workflow'))+[IO.Path]::DirectorySeparatorChar
if(!$run.StartsWith($allowedRoot,[StringComparison]::OrdinalIgnoreCase)){throw 'Batch must stay inside target/course-workflow'}
$manifest=Join-Path $run 'processes.json'
. "$PSScriptRoot/environment-common.ps1"
if($Action -eq 'Stop'){
    if(Test-Path -LiteralPath $manifest){
        $owned=@(Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json)
        [array]::Reverse($owned)
        foreach($record in $owned){
            $process=Get-RecordedProcess $record $record.name
            if($process){Stop-Process -InputObject $process; $process.WaitForExit(5000) | Out-Null; Write-Output "Stopped owned $($record.role) PID $($record.pid)"}
        }
    }
    return
}
foreach($port in @(18496,18500)){
    if(Test-LocalPort $port){throw "Port $port has another owner; refusing replacement"}
}
if(Test-Path -LiteralPath $manifest){
    if(!$RecoverUnspentStartup){throw 'Batch already has a process manifest; do not restart its paid budget'}
    $previous=@(Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json)
    if(@($previous | Where-Object {Get-RecordedProcess $_ $_.name}).Count -gt 0){throw 'Previous owned process is still running'}
    foreach($name in @('model-starts.jsonl','model-calls.jsonl','seven-turn-report.json')){
        if(Test-Path -LiteralPath "$run/$name"){throw 'A model batch was already attempted; no reset allowed'}
    }
    Copy-Item -LiteralPath $manifest -Destination "$run/processes-unspent-startup.json"
}
foreach($port in @(19886,19883,3306,6379,8848,9848,9849)){
    if(!(Test-LocalPort $port)){throw "Required existing dependency $port unavailable"}
}
$cpFile=Join-Path $run 'runtime-classpath.txt'
if(!(Test-Path -LiteralPath $cpFile)){throw 'Generate the isolated runtime classpath first'}
$classRoots=@()
foreach($module in @('tj-aigc','tj-api','tj-common','tj-auth/tj-auth-common','tj-auth/tj-auth-resource-sdk')){
    $source=Join-Path $repo "$module/target/classes"
    if(!(Test-Path -LiteralPath $source)){throw "Compiled classes missing: $module"}
    $destination=Join-Path $run ('classes/'+$module.Replace('/','-'))
    if(Test-Path -LiteralPath $destination){
        if(!$RecoverUnspentStartup){throw 'Immutable class snapshot already exists'}
    }else{
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
        Copy-Item -LiteralPath $source -Destination $destination -Recurse
    }
    $classRoots+=$destination
}
$classpath=($classRoots -join ';')+';'+([IO.File]::ReadAllText($cpFile).Trim())
$records=[Collections.Generic.List[object]]::new()
function Start-Owned([string]$Role,[string]$File,[string[]]$Arguments){
    $quoted=$Arguments | ForEach-Object {'"'+$_.Replace('"','\"')+'"'}
    $process=Start-Process -FilePath $File -ArgumentList $quoted -WorkingDirectory $repo -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput "$run/$Role.stdout.log" -RedirectStandardError "$run/$Role.stderr.log"
    $records.Add([pscustomobject]@{role=$Role;pid=$process.Id;started=$process.StartTime.ToUniversalTime().ToString('o');name=$process.ProcessName})
    Write-EnvironmentJson $manifest @($records.ToArray())
    Write-Output "Started owned $Role PID $($process.Id)"
}
$names=@('DEEPSEEK_API_KEY','COURSE_REPLAY_PROVIDER_KEY','COURSE_REPLAY_MODEL_KEY')
$saved=@{}
foreach($name in $names){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try{
    foreach($scope in @('Process','User','Machine')){
        $value=[Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY',$scope)
        if($value){$env:COURSE_REPLAY_PROVIDER_KEY=$value;break}
    }
    if(!$env:COURSE_REPLAY_PROVIDER_KEY){throw 'Configured provider credential unavailable'}
    $env:COURSE_REPLAY_MODEL_KEY=[guid]::NewGuid().ToString('N')
    $proxyArguments=@('-X','utf8','-u',"$PSScriptRoot/course-live-proxy.py",
        '--port','18500','--course-port','19886','--search-port','19883','--output',$run,'--limit',"$ProviderRequestLimit")
    if($RecoverUnspentStartup){$proxyArguments+='--recover-unspent-startup'}
    Start-Owned 'proxy' (Get-Command python).Source $proxyArguments
    $env:COURSE_REPLAY_PROVIDER_KEY=$null
    $env:DEEPSEEK_API_KEY=$env:COURSE_REPLAY_MODEL_KEY
    $arguments=@('-Xms64m','-Xmx384m','-XX:MaxDirectMemorySize=64m','-XX:ActiveProcessorCount=2','-Dfile.encoding=UTF-8',
        '-cp',$classpath,'com.tianji.AIGCApplication','--spring.profiles.active=course-seven-evaluation',
        '--server.address=127.0.0.1','--server.port=18496',
        '--spring.cloud.nacos.server-addr=127.0.0.1:8848','--spring.cloud.nacos.config.namespace=tianji-local-boot3',
        '--spring.cloud.nacos.discovery.namespace=tianji-local-boot3','--spring.cloud.nacos.discovery.register-enabled=false',
        '--spring.cloud.sentinel.enabled=false','--tj.ai.base-url=http://127.0.0.1:18500',
        '--spring.ai.openai.base-url=http://127.0.0.1:18500','--tj.ai.model=deepseek-flash',
        '--spring.ai.retry.max-attempts=1','--tj.ai.knowledge.enabled=false','--tj.ai.course-retrieval.enabled=false',
        '--tj.ai.course-retrieval.sync.enabled=false','--tj.ai.audio.enabled=false',
        '--tj.ai.memory.type=mysql','--tj.ai.prompt.system.chat.data-id=',
        '--spring.cloud.openfeign.client.config.course.url=http://127.0.0.1:18500/course-api',
        '--spring.cloud.openfeign.client.config.search-service.url=http://127.0.0.1:18500/search-api',
        '--tj.ai.mcp.enabled=false','--tj.ai.mcp.course-detail-source=local',
        '--tj.ai.knowledge.observation.langfuse.enabled=true',
        '--tj.ai.knowledge.observation.langfuse.endpoint=http://127.0.0.1:18500/v1/traces',
        '--tj.ai.knowledge.observation.langfuse.public-key=replay-public',
        '--tj.ai.knowledge.observation.langfuse.secret-key=replay-secret',
        '--management.tracing.opentelemetry.export.schedule-delay=1s',
        '--spring.ai.chat.observations.log-prompt=true','--spring.ai.chat.observations.log-completion=true',
        '--logging.level.com.tianji=INFO')
    if($CaptureEffectiveConfig){
        $arguments+=@('--management.endpoints.web.exposure.include=health,env',
            '--management.endpoint.env.show-values=always','--spring.cloud.nacos.config.refresh-enabled=false',
            '--tj.ai.prompt.system.agents.route.data-id=','--tj.ai.prompt.system.agents.recommend.data-id=',
            '--tj.ai.prompt.system.agents.consult.data-id=','--tj.ai.prompt.system.agents.knowledge.data-id=')
    }
    Write-EnvironmentJson "$run/effective-overrides.json" @{arguments=$arguments;sourcePorts=@(19886,19883);classRoots=$classRoots;providerRequestLimit=$ProviderRequestLimit}
    $argumentFile=Join-Path $run 'java-arguments.txt'
    $escaped=@($arguments | ForEach-Object {'"'+$_.Replace('\','\\').Replace('"','\"')+'"'})
    [IO.File]::WriteAllLines($argumentFile,$escaped,[Text.UTF8Encoding]::new($false))
    Start-Owned 'aigc' 'D:/Java/jdk-17/bin/java.exe' @('@'+$argumentFile)
}catch{
    foreach($record in $records){$owned=Get-RecordedProcess $record $record.name;if($owned){Stop-Process -InputObject $owned}}
    throw
}finally{
    foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
}
