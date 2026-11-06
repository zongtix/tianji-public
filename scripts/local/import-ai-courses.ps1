param(
    [string]$JavaHome = 'D:/Java/jdk-17',
    [switch]$AllDemo,
    [switch]$PublishedPilot
)

$ErrorActionPreference = 'Stop'
if ($AllDemo -and $PublishedPilot) { throw 'Choose either AllDemo or PublishedPilot.' }
. (Join-Path $PSScriptRoot 'environment-common.ps1')
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$jar = Join-Path $repo 'tj-aigc/target/tj-aigc.jar'
if (!(Test-Path -LiteralPath $jar)) { throw 'Build tj-aigc before running this command.' }
$CourseIds = @([long]910000000000010003)
if ($AllDemo) { $CourseIds = @(10001..10012 | ForEach-Object { [long]910000000000000000 + $_ }) }
if ($PublishedPilot) { $CourseIds = @([long]1589888774267072513, [long]1589905661084430337) }
$remote = Get-NacosText 'aigc-service.yaml'
$local = Get-Content -LiteralPath (Join-Path $repo 'tj-aigc/src/main/resources/application.yml') -Raw
$database = Get-NacosText 'shared-mybatis.yaml'
$names = @('DASHSCOPE_API_KEY', 'DASHSCOPE_BASE_URL')
$savedEncoding = $OutputEncoding
$OutputEncoding = [Text.UTF8Encoding]::new($false)
$saved = @{}
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    foreach ($name in $names) {
        foreach ($scope in @('Process', 'User', 'Machine')) {
            $value = [Environment]::GetEnvironmentVariable($name, $scope)
            if (![string]::IsNullOrWhiteSpace($value)) {
                [Environment]::SetEnvironmentVariable($name, $value, 'Process')
                break
            }
        }
    }
    function Read-Setting([string]$Path) {
        $value = Get-YamlScalar $remote $Path
        if ($null -eq $value) { $value = Get-YamlScalar $local $Path }
        return Resolve-EnvironmentValue $value
    }
    $properties = @{}
    foreach ($path in @('spring.elasticsearch.uris', 'spring.elasticsearch.username', 'spring.elasticsearch.password',
            'tj.ai.knowledge.embedding.api-key', 'tj.ai.knowledge.embedding.base-url',
            'tj.ai.knowledge.embedding.model', 'tj.ai.knowledge.embedding.dimensions')) {
        $properties[$path] = Read-Setting $path
    }
    $properties['spring.elasticsearch.connection-timeout'] = '3s'
    $properties['spring.elasticsearch.socket-timeout'] = '30s'
    $probes = @(@{ query = '我已经会 Java 基础，想解决多个请求同时扣库存的问题'; expectedCourseId = [long]910000000000010003 })
    if ($AllDemo) {
        $probes += @(
            @{ query = '订单列表查询太慢，想学习执行计划和索引优化'; expectedCourseId = [long]910000000000010004 },
            @{ query = '课程详情访问量很大，想减轻数据库压力并防止热点缓存失效'; expectedCourseId = [long]910000000000010005 },
            @{ query = '消息重复消费导致积分加了两次，想学习重试和幂等处理'; expectedCourseId = [long]910000000000010006 },
            @{ query = '没有编程经验，希望从零学习 Java'; expectedCourseId = [long]910000000000010001 },
            @{ query = '想用脚本批量整理 Excel 文件，减少重复办公操作'; expectedCourseId = [long]910000000000010010 }
        )
    }
    if ($PublishedPilot) {
        $probes = @(
            @{ query = '我有 Java 后端开发经验，工作中需要兼顾前端，想快速学习 HTML、CSS、JavaScript 和常用前端框架'; expectedCourseId = [long]1589888774267072513 },
            @{ query = '我熟练使用 Spring Boot 和 MyBatis，想学习微服务架构演变与 Spring Cloud'; expectedCourseId = [long]1589905661084430337 }
        )
    }
    $jdbc = Resolve-EnvironmentValue ((Get-YamlScalar $database 'spring.datasource.url').Replace('${tj.jdbc.database}', 'tj_course'))
    $jdbc = $jdbc.Replace('autoReconnect=true', 'autoReconnect=false') + '&connectTimeout=5000&socketTimeout=10000'
    $inputData = @{
        properties = $properties; jdbcUrl = $jdbc
        username = Resolve-EnvironmentValue (Get-YamlScalar $database 'spring.datasource.username')
        password = Resolve-EnvironmentValue (Get-YamlScalar $database 'spring.datasource.password')
        courseIds = @($CourseIds); probes = @($probes)
    }
    $runDir = Join-Path $repo 'target/ai-course-import'
    New-Item -ItemType Directory -Force -Path $runDir | Out-Null
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $prefix = if ($PublishedPilot) { 'published-pilot' } else { 'import' }
    $report = Join-Path $runDir "$prefix-$stamp.json"
    $log = Join-Path $runDir "$prefix-$stamp.log"
    $inputData | ConvertTo-Json -Depth 8 -Compress | & (Join-Path $JavaHome 'bin/java.exe') '-Dfile.encoding=UTF-8' `
        '-Dloader.main=com.tianji.aigc.knowledge.course.CourseImportCommand' '-cp' $jar `
        'org.springframework.boot.loader.launch.PropertiesLauncher' $report *> $log
    if ($LASTEXITCODE -ne 0) {
        $secrets = @($properties['spring.elasticsearch.password'], $properties['tj.ai.knowledge.embedding.api-key'], $inputData.password)
        Get-Content -LiteralPath $log -Tail 15 | ForEach-Object {
            $line = $_
            foreach ($secret in $secrets) {
                if (![string]::IsNullOrWhiteSpace($secret)) { $line = $line.Replace($secret, '[REDACTED]') }
            }
            $line
        }
        throw "Course import or retrieval verification failed; inspect imports in $report before retrying. Log: $log"
    }
    Get-Content -LiteralPath $log | Where-Object { $_ -like 'COURSE_IMPORT_OK*' }
    Write-Output "Course import and retrieval report: $report"
} finally {
    $OutputEncoding = $savedEncoding
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}
