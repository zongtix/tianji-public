param(
    [switch]$LiveService,
    [string]$CourseServiceUrl = 'http://127.0.0.1:8086',
    [string]$JavaHome = 'D:/Java/jdk-17'
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$output = Join-Path $repo 'target/mcp-evaluation'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$savedJava = $env:JAVA_HOME
$savedPath = $env:Path
function Invoke-McpCheck([string]$Name, [string[]]$MavenArguments) {
    $log = Join-Path $output ($Name + '.log')
    & mvn @MavenArguments *> $log
    if ($LASTEXITCODE -ne 0) {
        Get-Content -LiteralPath $log -Tail 25
        throw "MCP check failed: $Name; see $log"
    }
    Select-String -LiteralPath $log -Pattern 'Tests run:|BUILD SUCCESS' | ForEach-Object { $_.Line }
}
Push-Location $repo
try {
    $env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
    $env:Path = "$env:JAVA_HOME/bin;$savedPath"
    $tests = @('CourseMcpClientTest','McpClientConfigTest','CourseToolsTest','CourseRecommendationToolsTest',
        'CourseRecommendationEvidenceTest','CourseBrowseToolsTest','CourseSemanticToolsTest','CourseToolPreparationTest',
        'AgentWorkflowTest','ChatStreamingContractTest','ChatStopMemoryTest','ChatRedisCoordinationTest',
        'ChatObservabilityTest','KnowledgeChatRoutingTest','KnowledgeChatClientTest','RagObservabilityTest') -join ','
    Invoke-McpCheck 'client-regression' @('-B','-pl','tj-aigc','-am',"-Dtest=$tests",'-Dsurefire.failIfNoSpecifiedTests=false','test')
    Invoke-McpCheck 'server-protocol' @('-B','-Pmcp-pilot','-pl','tj-course-mcp','-am','-Dtest=CourseMcpServerTest',
        '-Dsurefire.failIfNoSpecifiedTests=false','test')
    if ($LiveService) {
        $uri = [uri]$CourseServiceUrl
        if ($uri.Scheme -notin @('http','https') -or $uri.UserInfo -or $uri.Query -or $uri.Fragment) {
            throw 'Use a course service origin without credentials, query or fragment'
        }
        Invoke-McpCheck 'live-service' @('-B','-Pmcp-pilot','-pl','tj-course-mcp','-am','-Dtest=CourseMcpLiveServiceTest',
            '-Dmcp.live.enabled=true',"-Dmcp.live.course-url=$CourseServiceUrl",'-Dsurefire.failIfNoSpecifiedTests=false','test')
    }
    Write-Output "Evidence: $output; real-model acceptance is separate and is not implied by these checks."
} finally {
    Pop-Location
    $env:JAVA_HOME = $savedJava
    $env:Path = $savedPath
}
