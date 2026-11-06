param([string]$Report='core-report.json',[string]$Case='mcp-detail',[switch]$QueryOnly)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
. "$repo/scripts/local/environment-common.ps1"
$settings=@{}
Get-Content -LiteralPath (Join-Path $env:USERPROFILE '.tianji/aigc-langfuse.properties') | ForEach-Object{
    if($_ -match '^\s*([^#!][^=]*)=(.*)$'){$settings[$matches[1].Trim()]=$matches[2].Trim()}
}
$names=@('LANGFUSE_OTLP_TRACES_ENDPOINT','LANGFUSE_PUBLIC_KEY','LANGFUSE_SECRET_KEY',
    'MCP_EVAL_LANGFUSE_ENDPOINT','MCP_EVAL_LANGFUSE_PUBLIC','MCP_EVAL_LANGFUSE_SECRET')
$saved=@{}
foreach($name in $names){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try{
    foreach($name in @('LANGFUSE_OTLP_TRACES_ENDPOINT','LANGFUSE_PUBLIC_KEY','LANGFUSE_SECRET_KEY')){
        foreach($scope in @('Process','User','Machine')){
            $value=[Environment]::GetEnvironmentVariable($name,$scope)
            if($value){[Environment]::SetEnvironmentVariable($name,$value,'Process');break}
        }
    }
    $env:MCP_EVAL_LANGFUSE_ENDPOINT=Resolve-EnvironmentValue $settings['tj.ai.knowledge.observation.langfuse.endpoint']
    $env:MCP_EVAL_LANGFUSE_PUBLIC=Resolve-EnvironmentValue $settings['tj.ai.knowledge.observation.langfuse.public-key']
    $env:MCP_EVAL_LANGFUSE_SECRET=Resolve-EnvironmentValue $settings['tj.ai.knowledge.observation.langfuse.secret-key']
    $arguments=@('-u',"$PSScriptRoot/publish-trace.py",'--publish','--report',$Report,'--case',$Case)
    if($QueryOnly){$arguments+='--query-only'}
    & python @arguments
    if($LASTEXITCODE -ne 0){throw 'Langfuse trace verification failed'}
}finally{
    foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
}
