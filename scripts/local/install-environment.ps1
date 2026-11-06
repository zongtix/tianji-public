param(
    [string]$IdeaConfig = (Join-Path $env:APPDATA 'JetBrains/IntelliJIdea2026.1'),
    [string]$StartupDirectory = [Environment]::GetFolderPath('Startup'),
    [string]$WorkspacePath = (Join-Path $PSScriptRoot '../../.idea/workspace.xml'),
    [switch]$PrepareOnly
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$stage = Join-Path $repo 'target/local-environment/install'
New-Item -ItemType Directory -Force -Path $stage | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)
$powershell = Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
$environmentScript = Join-Path $repo 'scripts/local/environment.ps1'

# Keep the existing business/Hermes boot entries intact; this adds only the missing supervisor.
$boot = "Set ws = CreateObject(""WScript.Shell"")`r`n" +
    "ws.Run """"""$powershell"""" -NoProfile -ExecutionPolicy Bypass -File """"$environmentScript"""" -Action Start -Background"", 0, False`r`n"
[IO.File]::WriteAllText((Join-Path $stage 'Tianji-Environment.vbs'), $boot, $utf8)

$tools = [xml]'<toolSet name="Tianji" />'
foreach ($target in @('Core', 'Aigc', 'Search')) {
    $tool = $tools.CreateElement('tool')
    foreach ($attribute in @{name="Prepare $target environment"; description='Restore Tianji tunnels and check dependencies before Java starts';
            showInMainMenu='true'; showInEditor='false'; showInProject='false'; showInSearchPopup='false';
            disabled='false'; useConsole='true'; showConsoleOnStdOut='false'; showConsoleOnStdErr='true'; synchronizeAfterRun='false'}.GetEnumerator()) {
        $tool.SetAttribute($attribute.Key, $attribute.Value)
    }
    $exec = $tools.CreateElement('exec')
    foreach ($entry in @{
            COMMAND=$powershell
            PARAMETERS="-NoProfile -ExecutionPolicy Bypass -File `"`$ProjectFileDir`$/scripts/local/environment.ps1`" -Action Start -Target $target"
            WORKING_DIRECTORY='$ProjectFileDir$'
        }.GetEnumerator()) {
        $option = $tools.CreateElement('option')
        $option.SetAttribute('name', $entry.Key); $option.SetAttribute('value', $entry.Value)
        $null = $exec.AppendChild($option)
    }
    $null = $tool.AppendChild($exec)
    $null = $tools.DocumentElement.AppendChild($tool)
}
$tools.Save((Join-Path $stage 'Tianji.xml'))

$original = [IO.File]::ReadAllText($WorkspacePath)
[xml]$workspace = $original
$count = 0
$updated = [regex]::Replace($original, '(?s)<configuration\b[^>]*type="SpringBootApplicationConfigurationType"[^>]*>.*?</configuration>', {
    param($match)
    $block = $match.Value
    if ($block -notmatch '<module name="[^\"]+"' -or $block -notmatch '<option name="SPRING_BOOT_MAIN_CLASS" value="[^\"]+"') { return $block }
    $target = if ($block -match 'value="com\.tianji\.AIGCApplication"') { 'Aigc' }
        elseif ($block -match 'value="com\.tianji\.search\.SearchApplication"') { 'Search' } else { 'Core' }
    $task = '<option name="ToolBeforeRunTask" enabled="true" actionId="Tool_Tianji_Prepare ' + $target + ' environment" />'
    # Replace this installer's own previous hook only; preserve all other before-run tasks.
    $block = [regex]::Replace($block, '\s*<option name="ToolBeforeRunTask"[^>]*actionId="Tool_Tianji_Prepare [^"]*"[^>]*/>', '')
    $newline = if ($block.Contains("`r`n")) { "`r`n" } else { "`n" }
    if ($block -match '<method v="2"\s*/>') {
        $block = [regex]::Replace($block, '<method v="2"\s*/>', '<method v="2">' + $newline + '        ' + $task + $newline + '      </method>')
    } elseif ($block -match '<method v="2">') {
        $block = $block.Replace('<method v="2">', '<method v="2">' + $newline + '        ' + $task)
    } else {
        $block = $block.Replace('</configuration>', '  <method v="2">' + $newline + '        ' + $task + $newline + '      </method>' + $newline + '    </configuration>')
    }
    $script:count++
    return $block
})
[xml]$validated = $updated
if ($count -eq 0) { throw 'No Spring Boot run configurations found; nothing installed.' }
[IO.File]::WriteAllText((Join-Path $stage 'workspace.xml'), $updated, $utf8)
$plan = @{
    workspace = (Resolve-Path -LiteralPath $WorkspacePath).Path
    tools = Join-Path $IdeaConfig 'tools/Tianji.xml'
    startup = Join-Path $StartupDirectory 'Tianji-Environment.vbs'
    runConfigurations = $count
}
$plan | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $stage 'plan.json') -Encoding UTF8
if ($PrepareOnly) { Write-Output "Prepared startup entry and $count IDEA hooks under $stage"; return }

$backup = Join-Path $repo ('target/local-environment/backups/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Force -Path $backup | Out-Null
Copy-Item -LiteralPath $WorkspacePath -Destination (Join-Path $backup 'workspace.xml')
foreach ($entry in @(@{path=$plan.tools;name='Tianji.xml'}, @{path=$plan.startup;name='Tianji-Environment.vbs'})) {
    if (Test-Path -LiteralPath $entry.path) { Copy-Item -LiteralPath $entry.path -Destination (Join-Path $backup $entry.name) }
}
if ([IO.File]::ReadAllText($WorkspacePath) -cne $original) { throw 'IDEA settings changed concurrently; rerun installation.' }
New-Item -ItemType Directory -Force -Path (Split-Path $plan.tools) | Out-Null
Copy-Item -LiteralPath (Join-Path $stage 'Tianji.xml') -Destination $plan.tools
Copy-Item -LiteralPath (Join-Path $stage 'Tianji-Environment.vbs') -Destination $plan.startup
[IO.File]::WriteAllText($WorkspacePath, $updated, $utf8)
Write-Output "Installed boot supervisor and preflight for $count IDEA run configurations. Backup: $backup"
Write-Output 'Reload this project / restart IDEA to load the new external tools and before-run tasks.'
