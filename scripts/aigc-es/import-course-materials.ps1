param(
    [string]$JavaHome = 'D:/Java/jdk-17',
    [string]$Index = 'tj_aigc_course_materials_v1_1024'
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$jar = Join-Path $repo 'tj-aigc/target/tj-aigc.jar'
$runDir = Join-Path $repo 'target/aigc-es/course-materials'
if (!(Test-Path -LiteralPath $jar)) { throw 'Build tj-aigc first: mvn -pl tj-aigc -am -DskipTests package' }
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$names = @('DASHSCOPE_API_KEY', 'DASHSCOPE_BASE_URL', 'TJ_AIGC_ES_PASSWORD', 'TJ_AI_COURSE_MATERIALS_INDEX')
$saved = @{}
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    foreach ($name in @('DASHSCOPE_API_KEY', 'DASHSCOPE_BASE_URL')) {
        $value = [Environment]::GetEnvironmentVariable($name, 'Process')
        if ([string]::IsNullOrWhiteSpace($value)) { $value = [Environment]::GetEnvironmentVariable($name, 'User') }
        if ([string]::IsNullOrWhiteSpace($value)) { $value = [Environment]::GetEnvironmentVariable($name, 'Machine') }
        if ([string]::IsNullOrWhiteSpace($value)) { throw "Missing environment variable: $name" }
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
    $env:TJ_AIGC_ES_PASSWORD = (Get-Content -LiteralPath (Join-Path $repo 'target/aigc-es/private/application-password.txt') -Raw).Trim()
    $env:TJ_AI_COURSE_MATERIALS_INDEX = $Index
    $log = Join-Path $runDir 'materials-import.log'
    $report = Join-Path $runDir 'materials-import.json'
    $java = Join-Path $JavaHome 'bin/java.exe'
    # Redirect via cmd so SLF4J warnings on stderr do not become terminating NativeCommandError.
    cmd /c "`"$java`" -Dloader.main=com.tianji.aigc.knowledge.material.CourseMaterialImportCommand -cp `"$jar`" org.springframework.boot.loader.launch.PropertiesLauncher `"$report`" > `"$log`" 2>&1"
    $code = $LASTEXITCODE
    if ($code -ne 0) {
        Get-Content -LiteralPath $log -Tail 40 | ForEach-Object {
            $line = $_
            foreach ($secret in @($env:DASHSCOPE_API_KEY, $env:TJ_AIGC_ES_PASSWORD)) {
                if (![string]::IsNullOrWhiteSpace($secret)) { $line = $line.Replace($secret, '[REDACTED]') }
            }
            $line
        }
        throw "Course material import failed; see $log"
    }
    Write-Output "Import report: $report"
    if (Test-Path -LiteralPath $report) { Get-Content -LiteralPath $report -Raw }
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}
