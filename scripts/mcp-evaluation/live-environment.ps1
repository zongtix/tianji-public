param(
    [ValidateSet('Start','Stop')][string]$Action='Start',
    [string]$JavaHome='D:/Java/jdk-17',
    [string]$RoundBudget='20s',
    [ValidatePattern('^[a-z0-9-]+$')][string]$RunName='live',
    [switch]$ClasspathCommonPrompt,
    [switch]$RestrictCandidates
)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$run=Join-Path $repo "target/mcp-evaluation/$RunName"
$manifestPath=Join-Path $run 'processes.json'
New-Item -ItemType Directory -Force -Path $run | Out-Null
. "$repo/scripts/local/environment-common.ps1"
if($Action -eq 'Stop'){
    if(Test-Path -LiteralPath $manifestPath){
        foreach($record in (Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json)){
            $owned=Get-RecordedProcess $record $record.name
            if($owned){Stop-Process -InputObject $owned; Write-Output "Stopped owned $($record.role) PID $($record.pid)"}
        }
    }
    return
}
foreach($port in @(18096,18097,18098,18100)){
    if(Test-LocalPort $port){throw "Port $port is occupied; refusing to replace its owner"}
}
$records=[Collections.Generic.List[object]]::new()
function Start-PilotProcess([string]$Role,[string]$File,[string[]]$Arguments){
    $quoted=$Arguments | ForEach-Object {'"'+$_.Replace('"','\"')+'"'}
    $process=Start-Process -FilePath $File -ArgumentList $quoted -WorkingDirectory $repo -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput "$run/$Role.stdout.log" -RedirectStandardError "$run/$Role.stderr.log"
    $records.Add([pscustomobject]@{role=$Role;pid=$process.Id;started=$process.StartTime.ToUniversalTime().ToString('o');name=$process.ProcessName})
    Write-EnvironmentJson $manifestPath @($records.ToArray())
    Write-Output "Started isolated $Role PID $($process.Id)"
}
$names=@('DEEPSEEK_API_KEY','DASHSCOPE_API_KEY','DASHSCOPE_BASE_URL','MCP_EVAL_PROVIDER_KEY','MCP_EVAL_MODEL_KEY',
    'TJ_MCP_SERVER_TOKEN','TJ_MCP_ALLOWED_COURSE_IDS','TJ_COURSE_SERVICE_URL','TJ_AI_MCP_TOKEN')
$saved=@{}
foreach($name in $names){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try{
    foreach($name in @('DEEPSEEK_API_KEY','DASHSCOPE_API_KEY','DASHSCOPE_BASE_URL')){
        foreach($scope in @('Process','User','Machine')){
            $value=[Environment]::GetEnvironmentVariable($name,$scope)
            if(![string]::IsNullOrWhiteSpace($value)){[Environment]::SetEnvironmentVariable($name,$value,'Process');break}
        }
    }
    if(!$env:DEEPSEEK_API_KEY){throw 'Configured model credential is unavailable'}
    $env:MCP_EVAL_PROVIDER_KEY=$env:DEEPSEEK_API_KEY
    $env:MCP_EVAL_MODEL_KEY=[guid]::NewGuid().ToString('N')
    $env:TJ_MCP_SERVER_TOKEN=[guid]::NewGuid().ToString('N')+[guid]::NewGuid().ToString('N')
    $env:TJ_AI_MCP_TOKEN=$env:TJ_MCP_SERVER_TOKEN
    $env:TJ_MCP_ALLOWED_COURSE_IDS='1589888774267072513,1589905661084430337'
    $env:TJ_COURSE_SERVICE_URL='http://127.0.0.1:8086'
    $python=(Get-Command python).Source
    Start-PilotProcess 'support' $python @('-u',"$PSScriptRoot/live-support.py",'--output',$run)
    $env:MCP_EVAL_PROVIDER_KEY=$null
    # Applications authenticate to the local proxy with a throwaway key, never the provider key.
    $env:DEEPSEEK_API_KEY=$env:MCP_EVAL_MODEL_KEY
    $java=Join-Path $JavaHome 'bin/java.exe'
    Start-PilotProcess 'course-mcp' $java @('-Xms32m','-Xmx192m','-XX:ActiveProcessorCount=2','-Dfile.encoding=UTF-8',
        '-jar',"$repo/tj-course-mcp/target/tj-course-mcp-1.0.0.jar",'--server.port=18097')
    $searchUrl=if($RestrictCandidates){'http://127.0.0.1:18100/search-api'}else{'http://127.0.0.1:8083'}
    $common=@('-Xms64m','-Xmx384m','-XX:MaxDirectMemorySize=64m','-XX:ActiveProcessorCount=2','-Dfile.encoding=UTF-8',
        '-jar',"$repo/tj-aigc/target/tj-aigc.jar",'--spring.profiles.active=mcp-evaluation',
        '--server.address=127.0.0.1','--spring.cloud.nacos.server-addr=127.0.0.1:8848',
        '--spring.cloud.nacos.config.namespace=tianji-local-boot3','--spring.cloud.nacos.discovery.namespace=tianji-local-boot3',
        '--spring.cloud.nacos.discovery.register-enabled=false','--spring.cloud.sentinel.enabled=false',
        '--tj.ai.base-url=http://127.0.0.1:18100','--spring.ai.openai.base-url=http://127.0.0.1:18100',
        '--tj.ai.model=deepseek-flash','--tj.ai.knowledge.enabled=false','--tj.ai.course-retrieval.enabled=false',
        "--tj.ai.mcp.round-budget=$RoundBudget",
        '--tj.ai.course-retrieval.sync.enabled=false','--tj.ai.audio.enabled=false',
        '--spring.cloud.openfeign.client.config.course.url=http://127.0.0.1:8086',
        "--spring.cloud.openfeign.client.config.search-service.url=$searchUrl",
        '--tj.ai.knowledge.observation.langfuse.enabled=true',
        '--tj.ai.knowledge.observation.langfuse.endpoint=http://127.0.0.1:18100/v1/traces',
        '--tj.ai.knowledge.observation.langfuse.public-key=pilot-public','--tj.ai.knowledge.observation.langfuse.secret-key=pilot-secret',
        '--management.tracing.opentelemetry.export.schedule-delay=1s','--logging.level.com.tianji=INFO')
    if($ClasspathCommonPrompt){$common+=@('--tj.ai.prompt.system.chat.data-id=')}
    Start-PilotProcess 'aigc-local' $java ($common+@('--server.port=18096','--tj.ai.mcp.enabled=false','--tj.ai.mcp.course-detail-source=local'))
    Start-PilotProcess 'aigc-mcp' $java ($common+@('--server.port=18098','--tj.ai.mcp.enabled=true','--tj.ai.mcp.course-detail-source=mcp',
        '--tj.ai.mcp.course-server-url=http://127.0.0.1:18100/mcp'))
}finally{
    foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
}
