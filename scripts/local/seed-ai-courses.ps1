param(
    [switch]$Apply,
    [switch]$Rehearse
)

$ErrorActionPreference = 'Stop'
if ($Apply -and $Rehearse) { throw 'Choose Apply or Rehearse, not both.' }
. (Join-Path $PSScriptRoot 'environment-common.ps1')
$source = Get-NacosText 'shared-mybatis.yaml'
$jdbc = (Get-YamlScalar $source 'spring.datasource.url').Replace('${tj.jdbc.database}', 'tj_course')
$connection = @{
    url = Resolve-EnvironmentValue $jdbc
    username = Resolve-EnvironmentValue (Get-YamlScalar $source 'spring.datasource.username')
    password = Resolve-EnvironmentValue (Get-YamlScalar $source 'spring.datasource.password')
}
$arguments = @((Join-Path $PSScriptRoot 'seed-ai-courses.py'))
if ($Apply) { $arguments += '--apply' }
if ($Rehearse) { $arguments += '--rehearse' }
$connection | ConvertTo-Json -Compress | & python @arguments
if ($LASTEXITCODE -ne 0) { throw 'AI course fixture operation failed.' }
