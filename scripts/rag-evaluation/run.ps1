param(
    [ValidateSet('A','APRIME','B','C','D','B_CONTEXT')][string]$Mode = 'A',
    [ValidateSet('current','control','conditions','context4','boundary-control','boundary','boundary-context4','focus-control','focus','precision-control','precision')][string]$Experiment = 'current',
    [switch]$Answers,
    [string[]]$Only = @(),
    [string]$Dataset = '',
    [string]$RunGroup = '',
    [switch]$InitializeCorpus,
    [string]$JavaHome = 'D:/Java/jdk-17'
)
# 正式评测必须传 -Dataset 指向含 tj_aigc_eval_* 的冻结 dataset.json。
# 默认 docs/rag-evaluation/cases.json 带生产 index 名，会被拒绝，禁止静默落到 Nacos 生产索引。
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$datasetPath = if ($Dataset) { (Resolve-Path -LiteralPath $Dataset).Path } else { "$repo/docs/rag-evaluation/cases.json" }
$datasetInfo = Get-Content -LiteralPath $datasetPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($datasetInfo.status -eq 'draft') { throw 'Prepare a frozen dataset with production chunk references before running.' }
if (!$datasetInfo.index -or $datasetInfo.index -notmatch '^tj_aigc_eval_[a-z0-9_]+$') {
    throw @"
Dataset.index is required and must match ^tj_aigc_eval_[a-z0-9_]+$.
Default docs/rag-evaluation/cases.json names a production index and is rejected.
Pass -Dataset to a frozen eval dataset (e.g. target/rag-optimization/<batch>/dataset.json or docs/rag-evaluation/results/<batch>/dataset.json).
"@
}
if ($RunGroup -and $RunGroup -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Invalid run group.' }
$snapshot = $null
$experimentPrefix = 'local-fixes-v2'
if ($Experiment -ne 'current') {
    $snapshotFile = "$repo/docs/rag-evaluation/local-fix-experiments.json"
    if ($Experiment.StartsWith('boundary')) {
        $snapshotFile = "$repo/docs/rag-evaluation/evidence-boundary-experiments.json"
        $experimentPrefix = 'evidence-boundary-v3'
    }
    if ($Experiment.StartsWith('focus')) {
        $snapshotFile = "$repo/docs/rag-evaluation/focus-experiments.json"
        $experimentPrefix = 'answer-focus-v4'
    }
    if ($Experiment.StartsWith('precision')) {
        $snapshotFile = "$repo/docs/rag-evaluation/precision-experiments.json"
        $experimentPrefix = 'answer-precision-v5'
    }
    $experiments = Get-Content -LiteralPath $snapshotFile -Raw | ConvertFrom-Json
    $snapshot = $experiments.experiments.$Experiment
    if ($Mode -ne $snapshot.mode) { throw "Experiment $Experiment freezes mode $($snapshot.mode); specify that mode." }
}
. "$repo/scripts/local/environment-common.ps1"
$run = Join-Path $repo 'target/rag-optimization'
New-Item -ItemType Directory -Force -Path "$run/classes" | Out-Null
foreach ($name in @('DASHSCOPE_API_KEY','DASHSCOPE_BASE_URL','DEEPSEEK_API_KEY',
        'TJ_AI_RERANK_API_KEY','TJ_AI_RERANK_ENDPOINT','TJ_AI_RERANK_MODEL')) {
    foreach ($scope in @('Process','User','Machine')) {
        $value = [Environment]::GetEnvironmentVariable($name, $scope)
        if (![string]::IsNullOrWhiteSpace($value)) { [Environment]::SetEnvironmentVariable($name, $value, 'Process'); break }
    }
}
$local = [IO.File]::ReadAllText("$repo/tj-aigc/src/main/resources/application.yml")
$remote = Get-NacosText 'aigc-service.yaml'
function Read-RagSetting([string]$key) {
    $value = Get-YamlScalar $remote $key
    if ($null -eq $value) { $value = Get-YamlScalar $local $key }
    return Resolve-EnvironmentValue $value
}
$properties = @{'tj.ai.knowledge.enabled'='true';'tj.ai.knowledge.store-type'='elasticsearch'}
foreach ($key in @('spring.elasticsearch.uris','spring.elasticsearch.username','spring.elasticsearch.password',
        'tj.ai.knowledge.index-name','tj.ai.knowledge.embedding.api-key','tj.ai.knowledge.embedding.base-url',
        'tj.ai.knowledge.embedding.model','tj.ai.knowledge.embedding.dimensions')) {
    $properties[$key] = Read-RagSetting $key
}
$properties['spring.elasticsearch.connection-timeout']='3s'
$properties['spring.elasticsearch.socket-timeout']='10s'
# Always override Nacos/local production index-name with the frozen eval index.
$properties['tj.ai.knowledge.index-name'] = $datasetInfo.index
$chatPromptId = Read-RagSetting 'tj.ai.prompt.system.chat.data-id'
$common = Get-NacosText $chatPromptId
$role = [IO.File]::ReadAllText("$repo/tj-aigc/src/main/resources/prompts/knowledge-system-message.txt")
$roleId = Get-YamlScalar $remote 'tj.ai.prompt.system.agents.KNOWLEDGE.data-id'
if (!$roleId) { $roleId = Get-YamlScalar $remote 'tj.ai.prompt.system.agents.knowledge.data-id' }
if ($roleId) { $role = Get-NacosText $roleId }
$prompt = ($common + "`n" + $role).Replace('{now}', '2026-10-24 12:00:00')
$sourceFiles = @(& rg --files "$repo/tj-aigc/src/main" "$repo/scripts/rag-evaluation") + @("$repo/pom.xml", "$repo/tj-aigc/pom.xml")
$sourceHashes = $sourceFiles | Sort-Object -Unique | ForEach-Object {
    $_.Substring($repo.Length).Replace('\','/') + ':' + (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash
}
$hasher = [Security.Cryptography.SHA256]::Create()
try { $sourceDigest = ([BitConverter]::ToString($hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes(($sourceHashes -join "`n"))))).Replace('-','').ToLowerInvariant() }
finally { $hasher.Dispose() }
$inputData = @{properties=$properties;answers=[bool]$Answers;mode=$Mode;experimentId=$datasetInfo.datasetVersion;
    initializeCorpus=[bool]$InitializeCorpus;runGroup=$RunGroup;
    codeVersion=(& git -C $repo rev-parse --short HEAD).Trim() + '-source-' + $sourceDigest;
    chatKey=(Read-RagSetting 'tj.ai.api-key');chatBaseUrl=(Read-RagSetting 'tj.ai.base-url');
    chatModel=(Read-RagSetting 'tj.ai.model');systemPrompt=$prompt}
$embeddingEndpoint = $properties['tj.ai.knowledge.embedding.base-url'].TrimEnd('/') + '/embeddings'
$chatEndpoint = $inputData.chatBaseUrl.TrimEnd('/') + '/chat/completions'
if ($embeddingEndpoint -cne 'https://maas.qianwenaiapi.com/compatible-mode/v1/embeddings' -or
        $chatEndpoint -cne 'https://api.deepseek.com/chat/completions') {
    throw 'Evaluation destination differs from the explicitly authorized endpoints; no model request was sent.'
}
if ($Only.Count) { $inputData['only']=$Only }
if ($snapshot) { $inputData['snapshot']=$snapshot; $inputData['experimentId']="$experimentPrefix-$Experiment" }
if ($Mode -in @('C','D')) {
    $rerankKey = if ($env:TJ_AI_RERANK_API_KEY) { $env:TJ_AI_RERANK_API_KEY } else { Read-RagSetting 'tj.ai.knowledge.rag.rerank.api-key' }
    $rerankEndpoint = if ($env:TJ_AI_RERANK_ENDPOINT) { $env:TJ_AI_RERANK_ENDPOINT } else { Read-RagSetting 'tj.ai.knowledge.rag.rerank.endpoint' }
    $rerankModel = if ($env:TJ_AI_RERANK_MODEL) { $env:TJ_AI_RERANK_MODEL } else { Read-RagSetting 'tj.ai.knowledge.rag.rerank.model' }
    if (!$rerankKey -or !$rerankModel) { throw "$Mode real evaluation unavailable: rerank configuration is missing." }
    if ($rerankEndpoint -cne 'https://maas.qianwenaiapi.com/api/v1/services/rerank/text-rerank/text-rerank') { throw 'Unexpected rerank endpoint.' }
    $inputData['rerank']=@{enabled=$true;apiKey=$rerankKey;endpoint=$rerankEndpoint;model=$rerankModel}
}
$savedJava = $env:JAVA_HOME; $savedPath = $env:Path; $savedEncoding = $OutputEncoding
Push-Location $repo
try {
    $env:JAVA_HOME=$JavaHome; $env:Path="$JavaHome/bin;$env:Path"
    & mvn -s "$env:USERPROFILE/.m2/settings.xml" -o -pl tj-aigc -am compile dependency:build-classpath "-Dmdep.outputFile=$run/classpath.txt" *> "$run/classpath.log"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot build evaluation classpath; see target/rag-optimization/classpath.log' }
    $classpath = "$repo/tj-aigc/target/classes;$repo/tj-api/target/classes;$repo/tj-common/target/classes;" + [IO.File]::ReadAllText("$run/classpath.txt").Trim()
    $utf8 = [Text.UTF8Encoding]::new($false)
    [IO.File]::WriteAllLines("$run/javac.args", @('-encoding','UTF-8','-cp',('"' + $classpath.Replace('\','/') + '"'),
            '-d',('"' + "$run/classes".Replace('\','/') + '"'),('"' + "$PSScriptRoot/RagEvaluation.java".Replace('\','/') + '"'),
            ('"' + "$PSScriptRoot/RagCorpus.java".Replace('\','/') + '"')), $utf8)
    & "$JavaHome/bin/javac.exe" "@$run/javac.args" *> "$run/eval-compile.log"
    if ($LASTEXITCODE -ne 0) { Get-Content "$run/eval-compile.log"; throw 'Evaluation compilation failed' }
    $OutputEncoding = [Text.UTF8Encoding]::new($false)
    $suffix = if ($Answers) { 'answers' } else { 'retrieval' }
    $reportRun = if ($RunGroup) { "$run/runs/$RunGroup" } else { "$run/runs/$Experiment-" + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') }
    New-Item -ItemType Directory -Force -Path $reportRun | Out-Null
    $output = "$reportRun/$Mode-$suffix.json"
    if (Test-Path -LiteralPath $output) { throw 'The report already exists; use a new run group.' }
    [IO.File]::WriteAllLines("$run/java-$Mode.args", @('-Dfile.encoding=UTF-8','-cp',('"' + "$run/classes;$classpath".Replace('\','/') + '"'),
            'RagEvaluation',('"' + $datasetPath.Replace('\','/') + '"'),('"' + $output.Replace('\','/') + '"')), $utf8)
    $savedErrorAction = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $inputData | ConvertTo-Json -Depth 10 -Compress | & "$JavaHome/bin/java.exe" "@$run/java-$Mode.args" *> "$reportRun/$Mode-$suffix.log"
        $evaluationExitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedErrorAction }
    if ($evaluationExitCode -ne 0) { throw "Evaluation failed; see $reportRun/$Mode-$suffix.log" }
    Get-Content "$reportRun/$Mode-$suffix.log" | Where-Object { $_ -match '^EVAL ' }
    Write-Output "Report: $output"
} finally {
    $OutputEncoding=$savedEncoding; $env:JAVA_HOME=$savedJava; $env:Path=$savedPath
    Pop-Location
}
