param()
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$private = Join-Path $repo 'target/aigc-redis8/private'
$workspace = Join-Path $repo '.idea/workspace.xml'
$propertiesPath = Join-Path $private 'idea-vector-redis.properties'
$password = (Get-Content -LiteralPath (Join-Path $private 'password.txt') -Raw).Trim()
if ([string]::IsNullOrWhiteSpace($password)) { throw 'Redis password file is empty.' }
$source = [System.IO.File]::ReadAllText($workspace)
[xml]$document = $source
$configuration = $document.SelectSingleNode('//component[@name="RunManager"]/configuration[@name="AIGCApplication"]')
if (!$configuration) { throw 'IDEA AIGCApplication run configuration not found.' }
$option = $configuration.SelectSingleNode('option[@name="VM_PARAMETERS"]')
$previous = if ($option) { $option.GetAttribute('value') } else { '' }
$location = 'file:' + $propertiesPath.Replace('\', '/')
$argument = '-Dspring.config.additional-location=' + $location
if ($argument.Contains(' ')) { $argument = '"' + $argument + '"' }
if ($previous.Contains('spring.config.additional-location=') -and !$previous.Contains($location)) {
    throw 'Existing additional config location needs review; no IDEA options changed.'
}
$updated = $previous
if (!$updated.Contains($location)) { $updated = ($previous + ' ' + $argument).Trim() }
$escaped = [System.Security.SecurityElement]::Escape($updated)
$pattern = '(?s)<configuration name="AIGCApplication"[^>]*>.*?</configuration>'
$match = [regex]::Match($source, $pattern)
if (!$match.Success) { throw 'Cannot locate the original AIGC configuration block.' }
$block = $match.Value
$newOption = '<option name="VM_PARAMETERS" value="' + $escaped + '" />'
if ($option) {
    $block = [regex]::Replace($block, '<option name="VM_PARAMETERS"[^>]*/>', [System.Text.RegularExpressions.MatchEvaluator]{ param($m) $newOption })
} else {
    $newline = if ($source.Contains("`r`n")) { "`r`n" } else { "`n" }
    $block = $block.Insert($block.IndexOf('>') + 1, $newline + '      ' + $newOption)
}
$result = $source.Substring(0, $match.Index) + $block + $source.Substring($match.Index + $match.Length)
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
Copy-Item -LiteralPath $workspace -Destination (Join-Path $private "idea-workspace-before-vector-$stamp.xml")
if ([System.IO.File]::ReadAllText($workspace) -cne $source) { throw 'IDEA settings changed concurrently; no settings overwritten.' }
$utf8 = New-Object System.Text.UTF8Encoding($false)
$escapedPassword = $password.Replace('\', '\\').Replace('=', '\=').Replace(':', '\:').Replace(' ', '\ ')
[System.IO.File]::WriteAllText($propertiesPath, "tj.ai.knowledge.redis.password=$escapedPassword`n", $utf8)
[System.IO.File]::WriteAllText($workspace, $result, $utf8)
Write-Output 'Configured AIGCApplication to load its Redis password from a local Git-ignored properties file.'
Write-Output 'IDEA XML contains only the file path; password was not printed or saved to Nacos.'
