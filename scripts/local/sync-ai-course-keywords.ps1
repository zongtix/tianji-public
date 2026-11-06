param([switch]$Apply)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$passwordPath = Join-Path $repo 'target/aigc-es/private/search-password.txt'
if (!(Test-Path -LiteralPath $passwordPath)) { throw 'Existing search credential file is missing.' }
$connection = @{
    username = 'tj_search_app'
    password = ([IO.File]::ReadAllText($passwordPath)).Trim()
}
$arguments = @((Join-Path $PSScriptRoot 'sync-ai-course-keywords.py'))
if ($Apply) { $arguments += '--apply' }
$connection | ConvertTo-Json -Compress | & python @arguments
if ($LASTEXITCODE -ne 0) { throw 'AI demo keyword sync failed; inspect its report.' }
