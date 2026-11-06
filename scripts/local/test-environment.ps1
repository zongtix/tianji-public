param([switch]$Integration)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'environment-common.ps1')
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$testRoot = Join-Path $repo ('target/local-environment-tests/' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
function Assert($Condition, [string]$Message) {
    if (!$Condition) { throw "FAIL: $Message" }
    Write-Output "PASS: $Message"
}

$yaml = @'
tj:
  ai:
    knowledge:
      enabled: ${TJ_TEST_ENVIRONMENT_ENABLED:true}
      store-type: elasticsearch
    memory:
      type: mysql
spring:
  elasticsearch:
    password: '${TJ_TEST_ENVIRONMENT_PASSWORD:private-default}'
'@
Assert ((Get-YamlScalar $yaml 'tj.ai.memory.type') -eq 'mysql') 'Nested sibling YAML paths stay separate'
Assert ((Get-YamlScalar $yaml 'tj.ai.knowledge.store-type') -eq 'elasticsearch') 'Hyphenated scalar property is read'
Assert ((Get-YamlScalar $yaml 'tj.ai.absent') -eq $null) 'Missing property remains missing'
Assert ((Resolve-EnvironmentValue (Get-YamlScalar $yaml 'tj.ai.knowledge.enabled')) -eq 'true') 'Placeholder fallback resolves'
$saved = $env:TJ_TEST_ENVIRONMENT_PASSWORD
try {
    $env:TJ_TEST_ENVIRONMENT_PASSWORD = 'override-for-test'
    Assert ((Resolve-EnvironmentValue (Get-YamlScalar $yaml 'spring.elasticsearch.password')) -eq 'override-for-test') 'Process environment overrides placeholder default'
} finally { $env:TJ_TEST_ENVIRONMENT_PASSWORD = $saved }
$missingFailed = $false
try { $null = Resolve-EnvironmentValue '${TJ_TEST_ENVIRONMENT_MISSING_VALUE}' } catch { $missingFailed = $true }
Assert $missingFailed 'Missing required placeholder fails explicitly'
$self = Get-Process -Id $PID
$record = @{pid=$PID;started=$self.StartTime.ToUniversalTime().ToString('o')}
Assert ($null -ne (Get-RecordedProcess $record $self.ProcessName)) 'Recorded process identity matches'
$record.started = $self.StartTime.AddSeconds(-1).ToUniversalTime().ToString('o')
Assert ($null -eq (Get-RecordedProcess $record $self.ProcessName)) 'Reused PID with different start time is rejected'
$defaultConfig = Import-PowerShellDataFile -LiteralPath (Join-Path $PSScriptRoot 'environment.psd1')
$kibanaTunnel = @($defaultConfig.Tunnels | Where-Object { $_.Name -eq 'kibana' -and $_.LocalPort -eq 5601 -and $_.RemotePort -eq 5601 -and !$_.When })
Assert ($kibanaTunnel.Count -eq 1) 'Default supervisor includes the Kibana page tunnel without a storage condition'

$fixture = Join-Path $testRoot 'workspace.xml'
$fixtureText = @'
<project><component name="RunManager">
<configuration name="AIGCApplication" type="SpringBootApplicationConfigurationType">
<module name="tj-aigc" /><option name="SPRING_BOOT_MAIN_CLASS" value="com.tianji.AIGCApplication" />
<option name="VM_PARAMETERS" value="-Xmx512m -Dexisting=true" />
<method v="2"><option name="Make" enabled="true" /><option name="UnrelatedTask" enabled="true" /></method>
</configuration></component></project>
'@
[IO.File]::WriteAllText($fixture, $fixtureText)
$idea = Join-Path $testRoot 'idea'
$startup = Join-Path $testRoot 'startup'
New-Item -ItemType Directory -Force -Path $startup | Out-Null
& (Join-Path $PSScriptRoot 'install-environment.ps1') -IdeaConfig $idea -StartupDirectory $startup -WorkspacePath $fixture
$once = [IO.File]::ReadAllText($fixture)
& (Join-Path $PSScriptRoot 'install-environment.ps1') -IdeaConfig $idea -StartupDirectory $startup -WorkspacePath $fixture
Assert ($once -ceq [IO.File]::ReadAllText($fixture)) 'IDEA installation is idempotent'
[xml]$installed = $once
Assert ($installed.SelectNodes('//option[@name="ToolBeforeRunTask"]').Count -eq 1) 'Exactly one owned before-run hook is installed'
Assert ($installed.SelectNodes('//option[@name="UnrelatedTask"]').Count -eq 1) 'Unrelated before-run task is preserved'
Assert ($installed.SelectSingleNode('//option[@name="VM_PARAMETERS"]').value -eq '-Xmx512m -Dexisting=true') 'Existing JVM arguments are preserved'
if (!$Integration) { Write-Output 'Local tests complete. Use -Integration for real temporary SSH recovery checks.'; return }

# Ephemeral ports: real ES/Kibana SSH forwards and an externally owned listener.
$reserve = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$reserve.Start(); $ownPort = $reserve.LocalEndpoint.Port; $reserve.Stop()
$reserve.Start(); $kibanaPort = $reserve.LocalEndpoint.Port; $reserve.Stop()
$foreign = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$foreign.Start(); $foreignPort = $foreign.LocalEndpoint.Port
$relativeRuntime = 'target/local-environment-tests/' + (Split-Path $testRoot -Leaf) + '/runtime'
$configPath = Join-Path $testRoot 'test.psd1'
$configText = @"
@{
SshHost = 'developer@tianji-dev.example.invalid'
SshKey = '%USERPROFILE%/.ssh/id_ed25519'
RuntimeDirectory = '$relativeRuntime'
CorePorts = @()
BusinessPorts = @()
Tunnels = @(
    @{ Name = 'test-es'; LocalPort = $ownPort; RemotePort = 19201 }
    @{ Name = 'test-kibana'; LocalPort = $kibanaPort; RemotePort = 5601 }
    @{ Name = 'foreign'; LocalPort = $foreignPort; RemotePort = 19201 }
)
}
"@
[IO.File]::WriteAllText($configPath, $configText)
$manager = Join-Path $PSScriptRoot 'environment.ps1'
$runtime = Join-Path $repo $relativeRuntime
function Wait-Port([int]$Port) {
    $deadline = (Get-Date).AddSeconds(35)
    do {
        if (Test-LocalPort $Port) { return }
        Start-Sleep -Milliseconds 300
    } while ((Get-Date) -lt $deadline)
    throw "Test tunnel did not recover: $Port. Inspect $runtime"
}
try {
    & $manager -Action Start -Background -ConfigPath $configPath
    Wait-Port $ownPort
    Wait-Port $kibanaPort
    $firstWatcher = Get-Content -LiteralPath (Join-Path $runtime 'watcher.json') -Raw | ConvertFrom-Json
    & $manager -Action Start -Background -ConfigPath $configPath
    $secondWatcher = Get-Content -LiteralPath (Join-Path $runtime 'watcher.json') -Raw | ConvertFrom-Json
    Assert ($firstWatcher.pid -eq $secondWatcher.pid) 'Repeated Start keeps the same supervisor'
    $children = Get-Content -LiteralPath (Join-Path $runtime 'children.json') -Raw | ConvertFrom-Json
    Assert ($null -eq $children.PSObject.Properties['foreign']) 'Existing listener is not replaced or adopted for termination'
    $child = $children.PSObject.Properties['test-es'].Value
    $owned = Get-RecordedProcess $child 'ssh'
    Assert ($null -ne $owned) 'Recovery test owns the exact SSH process being interrupted'
    Stop-Process -InputObject $owned
    $owned.WaitForExit(3000) | Out-Null
    Start-Sleep -Milliseconds 500
    Wait-Port $ownPort
    $after = Get-Content -LiteralPath (Join-Path $runtime 'children.json') -Raw | ConvertFrom-Json
    Assert ($after.PSObject.Properties['test-es'].Value.pid -ne $child.pid) 'Killed SSH child is automatically replaced'
    $response = try { Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$ownPort" -TimeoutSec 5 } catch { $_.Exception.Response }
    Assert ([int]$response.StatusCode -eq 401) 'Recovered tunnel reaches the actual authenticated ES server'
    $children = Get-Content -LiteralPath (Join-Path $runtime 'children.json') -Raw | ConvertFrom-Json
    $kibanaChild = $children.PSObject.Properties['test-kibana'].Value
    $kibanaOwned = Get-RecordedProcess $kibanaChild 'ssh'
    Assert ($null -ne $kibanaOwned) 'Kibana recovery test owns the exact SSH process being interrupted'
    Stop-Process -InputObject $kibanaOwned
    $kibanaOwned.WaitForExit(3000) | Out-Null
    Wait-Port $kibanaPort
    $after = Get-Content -LiteralPath (Join-Path $runtime 'children.json') -Raw | ConvertFrom-Json
    Assert ($after.PSObject.Properties['test-kibana'].Value.pid -ne $kibanaChild.pid) 'Killed Kibana SSH child is automatically replaced'
    $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$kibanaPort/login" -TimeoutSec 10
    Assert ([int]$response.StatusCode -eq 200) 'Recovered Kibana tunnel serves the actual login page'
    & $manager -Action Stop -ConfigPath $configPath
    Assert (!(Test-LocalPort $ownPort)) 'Stop closes its own tunnel'
    Assert (!(Test-LocalPort $kibanaPort)) 'Stop closes its own Kibana tunnel'
    Assert (Test-LocalPort $foreignPort) 'Stop preserves the externally owned listener'
} finally {
    & $manager -Action Stop -ConfigPath $configPath
    $foreign.Stop()
}
Write-Output 'Integration tests complete; business data was not changed.'
