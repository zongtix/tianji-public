param([ValidateSet('mysql', 'mongodb')][string]$Type = 'mysql')
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$uri = (Get-Content -LiteralPath (Join-Path $repo 'target/aigc-memory/private/mongodb-uri.txt') -Raw).Trim()
[Environment]::SetEnvironmentVariable('TJ_AIGC_MONGODB_URI', $uri, 'User')
[Environment]::SetEnvironmentVariable('TJ_AIGC_MEMORY_TYPE', $Type, 'User')
Write-Output "Saved $Type selection and MongoDB connection for the current Windows user. Restart IDEA to inherit them."
