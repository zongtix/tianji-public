# Read-only checks for the local course-guide demo. No login or model requests.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'environment-common.ps1')
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$checks = [Collections.Generic.List[object]]::new()

function Add-Result([string]$Name, [bool]$Passed, [string]$Detail) {
    $checks.Add([pscustomobject]@{ name = $Name; passed = $Passed; detail = $Detail })
    Write-Output (('{0} {1}: {2}' -f $(if ($Passed) { 'PASS' } else { 'FAIL' }), $Name, $Detail))
}

try {
    & (Join-Path $PSScriptRoot 'environment.ps1') -Action Check -Target Core | Out-Null
    Add-Result 'Core dependencies' $true 'Nacos configuration, datasource fields and Redis authentication checked'
} catch {
    Add-Result 'Core dependencies' $false 'Run scripts/local/environment.ps1 -Action Check -Target Core for diagnostics'
}

foreach ($service in @(
    @{ name = 'auth'; port = 8081 }, @{ name = 'user'; port = 8082 },
    @{ name = 'search'; port = 8083 }, @{ name = 'course'; port = 8086 },
    @{ name = 'exam'; port = 8089 }, @{ name = 'learning'; port = 8090 },
    @{ name = 'aigc'; port = 8096 }, @{ name = 'gateway'; port = 10010 },
    @{ name = 'frontend'; port = 18082 }
)) {
    Add-Result ($service.name + ' TCP') (Test-LocalPort $service.port) ('127.0.0.1:' + $service.port)
}

try {
    $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8096/actuator/health' -TimeoutSec 8
    Add-Result 'AIGC health' ($health.status -eq 'UP') ([string]$health.status)
} catch {
    Add-Result 'AIGC health' $false 'Health request failed'
}

try {
    # Vite HTML fallback checks Accept. A generic PowerShell request can return 404.
    $page = Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:18082/' -Headers @{ Accept = 'text/html,*/*' } -TimeoutSec 8
    $valid = $page.StatusCode -eq 200 -and $page.Content -match 'id="app"' -and $page.Content -match '/src/main.js'
    Add-Result 'Frontend HTML' $valid 'Browser HTML entry at http://localhost:18082/#/assistant/index'
} catch {
    Add-Result 'Frontend HTML' $false 'HTML entry unavailable'
}

foreach ($courseId in @('1589888774267072513', '1589905661084430337')) {
    try {
        $response = Invoke-RestMethod -Uri ('http://localhost:18082/api/cs/courses/baseInfo/' + $courseId) -TimeoutSec 8
        $valid = $response.code -eq 200 -and [string]$response.data.id -eq $courseId -and ![string]::IsNullOrWhiteSpace($response.data.name)
        Add-Result ('Course via frontend ' + $courseId) $valid 'Frontend /api -> gateway -> current course detail'
    } catch {
        Add-Result ('Course via frontend ' + $courseId) $false 'Current course detail unavailable through frontend proxy'
    }
}

$passed = @($checks | Where-Object { !$_.passed }).Count -eq 0
$report = @{
    checkedAt = (Get-Date).ToString('o')
    passed = $passed
    scope = 'Read-only dependency and HTTP checks; not model quality, login or full browser acceptance'
    modelRequests = 0
    entry = 'http://localhost:18082/#/assistant/index'
    checks = @($checks.ToArray())
}
$reportDir = Join-Path $repo 'target/course-guide-demo'
New-Item -ItemType Directory -Force -Path $reportDir | Out-Null
$reportPath = Join-Path $reportDir 'health.json'
[IO.File]::WriteAllText($reportPath, ($report | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
Write-Output ('Report: ' + $reportPath)
if (!$passed) { throw 'Course-guide checks failed. Inspect the failed entries; no services were restarted.' }
