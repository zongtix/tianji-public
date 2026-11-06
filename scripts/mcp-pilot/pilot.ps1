param(
    [ValidateSet('Start','Check','Stop','Ask','Web')][string]$Action='Check',
    [string]$Question,
    [string]$SessionId,
    [string]$FrontendRoot='D:/Java/tj-protal',
    [string]$JavaHome='D:/Java/jdk-17',
    [ValidateRange(10,240)][int]$WaitSeconds=150
)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
. "$repo/scripts/local/environment-common.ps1"
$runtime=Join-Path $repo 'target/mcp-pilot'
$currentPath=Join-Path $runtime 'current.json'
$approved=@('1589888774267072513','1589905661084430337')
$aigcPort=19096
$mcpPort=19097
$gatewayPort=19100
$coursePort=19886
$searchPort=19883

function Get-PilotRun {
    if(!(Test-Path -LiteralPath $currentPath)){return $null}
    $current=Get-Content -LiteralPath $currentPath -Raw | ConvertFrom-Json
    $path=[IO.Path]::GetFullPath([string]$current.directory)
    $prefix=[IO.Path]::GetFullPath((Join-Path $runtime 'runs'))+[IO.Path]::DirectorySeparatorChar
    if(!$path.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase)){throw 'Invalid pilot runtime directory'}
    return $path
}

function Get-PilotRecords([string]$Run) {
    if($Run -and (Test-Path -LiteralPath "$Run/processes.json")){
        return @(Get-Content -LiteralPath "$Run/processes.json" -Raw | ConvertFrom-Json)
    }
    return @()
}

function Stop-Pilot([string]$Run) {
    $records=@(Get-PilotRecords $Run)
    [array]::Reverse($records)
    foreach($record in $records){
        $owned=Get-RecordedProcess $record $record.name
        if($owned){
            try{
                Stop-Process -Id $owned.Id -Force
                $owned.WaitForExit(5000) | Out-Null
            }catch{
                # A child or launcher may exit between ownership checking and Stop.
                if(Get-RecordedProcess $record $record.name){throw}
            }
            Write-Host "Stopped owned $($record.role) PID $($record.pid)"
        }
    }
}

function Get-PilotJson([string]$Url) {
    $value=Invoke-RestMethod -Method Get -Uri $Url -TimeoutSec 12 -Headers @{'x-request-from'='feign'}
    if($value.PSObject.Properties.Name -contains 'code'){
        if($value.code -ne 200){throw 'Pilot read endpoint returned a non-success code'}
        return $value.data
    }
    return $value
}

function Get-PilotChecks {
    $checks=[ordered]@{aigc=$false;scopeGuard=$false;mcpRead=$false;courseReads=$false;searchScope=$false}
    $models=$null
    try{$checks.aigc=(Get-PilotJson "http://127.0.0.1:$aigcPort/actuator/health").status -eq 'UP'}catch{}
    try{
        $gateway=Get-PilotJson "http://127.0.0.1:$gatewayPort/status"
        $models=$gateway.models
        $checks.scopeGuard=$gateway.mode -eq 'pilot' -and
            (@(Compare-Object $approved @($gateway.approved_course_ids)).Count -eq 0)
    }catch{}
    try{$checks.mcpRead=(Get-PilotJson "http://127.0.0.1:$gatewayPort/pilot/check").passed -eq $true}catch{}
    try{
        $valid=$true
        foreach($id in $approved){$valid=$valid -and ([string](Get-PilotJson "http://127.0.0.1:$coursePort/course/$id/baseInfo").id -eq $id)}
        $checks.courseReads=$valid
    }catch{}
    try{
        $values=@(Get-PilotJson "http://127.0.0.1:$gatewayPort/search-api/courses/popular")
        $checks.searchScope=$values.Count -gt 0 -and @($values | Where-Object {[string]$_.id -notin $approved}).Count -eq 0
    }catch{}
    if($run -and @(Get-PilotRecords $run | Where-Object {$_.role -eq 'web'}).Count -gt 0){
        $checks.web=$false
        try{$checks.web=(Get-PilotJson 'http://127.0.0.1:18084/mcp-pilot-health').mode -eq 'course-pilot'}catch{}
    }
    return [pscustomobject]@{ready=@($checks.Values | Where-Object {$_ -ne $true}).Count -eq 0;
        checks=$checks;modelRequests=$models;aigcUrl="http://127.0.0.1:$aigcPort";mcpUrl="http://127.0.0.1:$mcpPort/mcp";
        courseUrl="http://127.0.0.1:$coursePort";searchUrl="http://127.0.0.1:$searchPort"}
}

$run=Get-PilotRun
if($run -and (Test-Path -LiteralPath "$run/sources.json")){
    $sources=Get-Content -LiteralPath "$run/sources.json" -Raw | ConvertFrom-Json
    $coursePort=[int]$sources.coursePort
    $searchPort=[int]$sources.searchPort
}
if($Action -eq 'Ask'){
    if([string]::IsNullOrWhiteSpace($Question)){throw 'Use -Question with a course question'}
    $aigc=@(Get-PilotRecords $run | Where-Object {$_.role -eq 'aigc'}) | Select-Object -First 1
    if(!$aigc -or !(Get-RecordedProcess $aigc $aigc.name) -or !(Test-LocalPort $aigcPort)){
        throw 'Owned pilot is not running; use -Action Start first'
    }
    $savedEncoding=$OutputEncoding
    $savedConsoleEncoding=[Console]::OutputEncoding
    try{
        $OutputEncoding=[Text.UTF8Encoding]::new($false)
        [Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
        @{question=$Question;sessionId=$SessionId;port=$aigcPort;stateFile=(Join-Path $runtime 'sessions.json')} |
            ConvertTo-Json -Compress | & python -X utf8 "$PSScriptRoot/chat.py"
        if($LASTEXITCODE -ne 0){throw 'Pilot question did not complete; see the message above'}
    }finally{
        [Console]::OutputEncoding=$savedConsoleEncoding
        $OutputEncoding=$savedEncoding
    }
    return
}
if($Action -eq 'Check'){
    if(!$run){throw 'Pilot has not been started; use -Action Start'}
    $owned=@(Get-PilotRecords $run)
    if($owned.Count -eq 0 -or @($owned | Where-Object {!(Get-RecordedProcess $_ $_.name)}).Count -gt 0){
        throw 'Owned pilot processes are not running; use -Action Start'
    }
    $status=Get-PilotChecks
    Write-EnvironmentJson "$run/check.json" $status
    $status | ConvertTo-Json -Depth 4
    if(!$status.ready){throw 'Pilot is not ready; inspect check.json and the current run logs'}
    return
}

$mutex=[Threading.Mutex]::new($false,'Local\TianjiMcpPilot')
$locked=$false
try{
    try{$locked=$mutex.WaitOne(0)}catch [Threading.AbandonedMutexException]{$locked=$true}
    if(!$locked){throw 'Another pilot Start/Stop operation is running'}
    if($Action -eq 'Stop'){
        Stop-Pilot $run
        Write-Host 'Pilot stopped. Reused services and saved pilot conversations are retained.'
        return
    }
    if($Action -eq 'Web'){
        $owned=@(Get-PilotRecords $run)
        if(!$run -or $owned.Count -eq 0 -or @($owned | Where-Object {!(Get-RecordedProcess $_ $_.name)}).Count -gt 0){
            throw 'Owned pilot is not running; use -Action Start first'
        }
        if(!(Get-PilotChecks).ready){throw 'Pilot dependencies are not ready; use Check'}
        $web=@($owned | Where-Object {$_.role -eq 'web'})
        if($web.Count -eq 0){
            if(Test-LocalPort 18084){throw 'Web port 18084 is already owned; refusing to replace its listener'}
            $frontend=(Resolve-Path -LiteralPath $FrontendRoot).Path
            foreach($file in @('node_modules/vite/package.json','scripts/mcp-pilot-proxy.mjs','src/utils/assistantPilot.mjs')){
                if(!(Test-Path -LiteralPath (Join-Path $frontend $file))){throw "Frontend pilot prerequisite missing: $file"}
            }
            $node=(Get-Command node).Source
            $webProcess=Start-Process -FilePath $node -ArgumentList @('"'+"$PSScriptRoot/web.mjs"+'"','"'+$frontend+'"') `
                -WorkingDirectory $repo -WindowStyle Hidden -PassThru `
                -RedirectStandardOutput "$run/web.stdout.log" -RedirectStandardError "$run/web.stderr.log"
            $web=@([pscustomobject]@{role='web';pid=$webProcess.Id;started=$webProcess.StartTime.ToUniversalTime().ToString('o');name=$webProcess.ProcessName})
            Write-EnvironmentJson "$run/processes.json" @($owned + $web)
        }
        $ready=$false
        $deadline=(Get-Date).AddSeconds(45)
        do{
            if(!(Get-RecordedProcess $web[0] $web[0].name)){throw 'Pilot web exited; inspect web.stderr.log'}
            try{$ready=(Get-PilotJson 'http://127.0.0.1:18084/mcp-pilot-health').mode -eq 'course-pilot'}catch{}
            if($ready){break}
            Start-Sleep -Milliseconds 500
        }while((Get-Date) -lt $deadline)
        if(!$ready){throw 'Pilot web startup timed out; inspect web.stderr.log; Stop retains unrelated services'}
        Write-Host 'Course pilot: http://127.0.0.1:18084/#/assistant/index?pilot=mcp'
        return
    }
    $oldRecords=@(Get-PilotRecords $run)
    $alive=@($oldRecords | Where-Object {Get-RecordedProcess $_ $_.name})
    if($alive.Count -gt 0){
        if($alive.Count -ne $oldRecords.Count){throw 'Pilot is partly running; use -Action Stop, then -Action Start'}
        $status=Get-PilotChecks
        $status | ConvertTo-Json -Depth 4
        if(!$status.ready){throw 'Existing pilot is not ready; use Check to inspect it'}
        Write-Host 'Existing pilot retained; no duplicate processes started.'
        return
    }
    foreach($port in @($aigcPort,$mcpPort,$gatewayPort)){
        if(Test-LocalPort $port){throw "Port $port is already owned; refusing to replace its listener"}
    }
    foreach($port in @(3306,6379,8848,9848,9849)){
        if(!(Test-LocalPort $port)){throw "Required local infrastructure port $port is unavailable; use scripts/local/environment.ps1"}
    }
    $reuseCourse=Test-LocalPort 8086
    $reuseSearch=Test-LocalPort 8083
    $coursePort=if($reuseCourse){8086}else{19886}
    $searchPort=if($reuseSearch){8083}else{19883}
    if(!$reuseCourse -and (Test-LocalPort $coursePort)){throw "Dedicated dependency port $coursePort is already owned"}
    if(!$reuseSearch -and (Test-LocalPort $searchPort)){throw "Dedicated dependency port $searchPort is already owned"}
    $java=Join-Path $JavaHome 'bin/java.exe'
    if(!(Test-Path -LiteralPath $java)){throw 'Java 17 executable is unavailable'}
    foreach($jar in @('tj-aigc/target/tj-aigc.jar','tj-course-mcp/target/tj-course-mcp-1.0.0.jar','tj-course/target/tj-course.jar','tj-search/target/tj-search.jar')){
        if(!(Test-Path -LiteralPath "$repo/$jar")){throw "Build required artifact: $jar"}
    }
    # Windows' Python app launcher can leave a worker after its own PID exits.
    # Record and stop the actual interpreter, rather than the launcher wrapper.
    $python=(& python -c 'import sys; print(sys._base_executable)').Trim()
    if($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath $python)){throw 'Python interpreter is unavailable'}
    $run=Join-Path $runtime ('runs/'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
    New-Item -ItemType Directory -Force -Path $run | Out-Null
    Write-EnvironmentJson $currentPath @{directory=$run}
    Write-EnvironmentJson "$run/sources.json" @{coursePort=$coursePort;searchPort=$searchPort;reusedCourse=$reuseCourse;reusedSearch=$reuseSearch}
    $records=[Collections.Generic.List[object]]::new()
    function Start-PilotProcess([string]$Role,[string]$File,[string[]]$Arguments){
        $quoted=$Arguments | ForEach-Object {'"'+$_.Replace('"','\"')+'"'}
        $child=Start-Process -FilePath $File -ArgumentList $quoted -WorkingDirectory $repo -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput "$run/$Role.stdout.log" -RedirectStandardError "$run/$Role.stderr.log"
        $records.Add([pscustomobject]@{role=$Role;pid=$child.Id;started=$child.StartTime.ToUniversalTime().ToString('o');name=$child.ProcessName})
        Write-EnvironmentJson "$run/processes.json" @($records.ToArray())
        Write-Host "Started pilot $Role PID $($child.Id)"
    }
    $names=@('DEEPSEEK_API_KEY','DASHSCOPE_API_KEY','DASHSCOPE_BASE_URL','MCP_EVAL_PROVIDER_KEY','MCP_EVAL_MODEL_KEY',
        'TJ_MCP_SERVER_TOKEN','TJ_AI_MCP_TOKEN','TJ_MCP_ALLOWED_COURSE_IDS','TJ_COURSE_SERVICE_URL')
    $saved=@{}
    foreach($name in $names){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
    try{
        foreach($service in @(@{name='course';port=$coursePort;reuse=$reuseCourse},@{name='search';port=$searchPort;reuse=$reuseSearch})){
            if($service.reuse){Write-Host "Retaining existing $($service.name) on $($service.port)";continue}
            Start-PilotProcess $service.name $java @('-Xms32m','-Xmx256m','-XX:ActiveProcessorCount=2','-Dfile.encoding=UTF-8',
                '-jar',"$repo/tj-$($service.name)/target/tj-$($service.name).jar",'--spring.profiles.active=local',
                '--server.address=127.0.0.1',"--server.port=$($service.port)",'--spring.cloud.nacos.server-addr=127.0.0.1:8848',
                '--spring.cloud.nacos.config.namespace=tianji-local-boot3','--spring.cloud.nacos.discovery.namespace=tianji-local-boot3',
                '--spring.cloud.nacos.discovery.register-enabled=false','--seata.enabled=false',
                '--spring.autoconfigure.exclude=com.tianji.common.autoconfigure.xxljob.XxlJobConfig',
                '--spring.rabbitmq.dynamic=false','--spring.rabbitmq.listener.simple.auto-startup=false',
                '--spring.rabbitmq.listener.direct.auto-startup=false','--logging.level.com.tianji=INFO')
        }
        foreach($name in @('DEEPSEEK_API_KEY','DASHSCOPE_API_KEY','DASHSCOPE_BASE_URL')){
            foreach($scope in @('Process','User','Machine')){
                $value=[Environment]::GetEnvironmentVariable($name,$scope)
                if(![string]::IsNullOrWhiteSpace($value)){[Environment]::SetEnvironmentVariable($name,$value,'Process');break}
            }
        }
        if(!$env:DEEPSEEK_API_KEY){throw 'Configured DeepSeek credential is unavailable'}
        $env:MCP_EVAL_PROVIDER_KEY=$env:DEEPSEEK_API_KEY
        $env:MCP_EVAL_MODEL_KEY=[guid]::NewGuid().ToString('N')
        $env:TJ_MCP_SERVER_TOKEN=[guid]::NewGuid().ToString('N')+[guid]::NewGuid().ToString('N')
        $env:TJ_AI_MCP_TOKEN=$env:TJ_MCP_SERVER_TOKEN
        $env:TJ_MCP_ALLOWED_COURSE_IDS=$approved -join ','
        $env:TJ_COURSE_SERVICE_URL="http://127.0.0.1:$coursePort"
        Start-PilotProcess 'gateway' $python @('-X','utf8','-u',"$repo/scripts/mcp-evaluation/live-support.py",'--pilot',
            '--port',"$gatewayPort",'--mcp-port',"$mcpPort",'--search-port',"$searchPort",'--output',$run)
        $env:MCP_EVAL_PROVIDER_KEY=$null
        $env:DEEPSEEK_API_KEY=$env:MCP_EVAL_MODEL_KEY
        Start-PilotProcess 'course-mcp' $java @('-Xms32m','-Xmx192m','-XX:ActiveProcessorCount=2','-Dfile.encoding=UTF-8',
            '-jar',"$repo/tj-course-mcp/target/tj-course-mcp-1.0.0.jar","--server.port=$mcpPort")
        Start-PilotProcess 'aigc' $java @('-Xms64m','-Xmx384m','-XX:MaxDirectMemorySize=64m','-XX:ActiveProcessorCount=2','-Dfile.encoding=UTF-8',
            '-jar',"$repo/tj-aigc/target/tj-aigc.jar",'--spring.profiles.active=mcp-pilot','--server.address=127.0.0.1',"--server.port=$aigcPort",
            '--spring.cloud.nacos.server-addr=127.0.0.1:8848','--spring.cloud.nacos.config.namespace=tianji-local-boot3',
            '--spring.cloud.nacos.discovery.namespace=tianji-local-boot3','--spring.cloud.nacos.discovery.register-enabled=false',
            '--spring.cloud.sentinel.enabled=false',"--tj.ai.base-url=http://127.0.0.1:$gatewayPort",
            "--spring.ai.openai.base-url=http://127.0.0.1:$gatewayPort",'--tj.ai.model=deepseek-flash',
            '--tj.ai.knowledge.enabled=false','--tj.ai.course-retrieval.enabled=false','--tj.ai.course-retrieval.sync.enabled=false',
            '--tj.ai.audio.enabled=false','--tj.ai.prompt.system.chat.data-id=',
            "--spring.cloud.openfeign.client.config.course.url=http://127.0.0.1:$coursePort",
            "--spring.cloud.openfeign.client.config.search-service.url=http://127.0.0.1:$gatewayPort/search-api",
            '--tj.ai.knowledge.observation.langfuse.enabled=false','--spring.ai.chat.observations.log-prompt=false',
            '--spring.ai.chat.observations.log-completion=false','--logging.level.com.tianji=INFO',
            '--tj.ai.mcp.enabled=true','--tj.ai.mcp.course-detail-source=mcp',"--tj.ai.mcp.course-server-url=http://127.0.0.1:$gatewayPort/mcp")
        $deadline=(Get-Date).AddSeconds($WaitSeconds)
        do{
            foreach($record in $records){if(!(Get-RecordedProcess $record $record.name)){throw "Pilot $($record.role) exited; inspect $run"}}
            $status=Get-PilotChecks
            if($status.ready){break}
            Start-Sleep -Seconds 2
        }while((Get-Date) -lt $deadline)
        Write-EnvironmentJson "$run/check.json" $status
        if(!$status.ready){throw "Pilot readiness timed out; inspect $run/check.json"}
        $status | ConvertTo-Json -Depth 4
        Write-Host 'Pilot ready. Ask sends your question and approved course data to the configured DeepSeek API.'
    }catch{
        Stop-Pilot $run
        throw
    }finally{
        foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
    }
}finally{
    if($locked){$mutex.ReleaseMutex()}
    $mutex.Dispose()
}
