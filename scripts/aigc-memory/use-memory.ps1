param([ValidateSet('mysql', 'mongodb')][string]$Type = 'mysql')
# Dot-source this script before starting Java from the same PowerShell session.
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
if ($Type -eq 'mongodb') {
    $uriPath = Join-Path $repo 'target/aigc-memory/private/mongodb-uri.txt'
    $env:TJ_AIGC_MONGODB_URI = (Get-Content -LiteralPath $uriPath -Raw).Trim()
}
$env:TJ_AIGC_MEMORY_TYPE = $Type
Write-Output "Selected $Type for Java processes started from this PowerShell session."
