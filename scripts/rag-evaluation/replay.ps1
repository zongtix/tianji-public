param([string]$JavaHome = 'D:/Java/jdk-17')
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$run = Join-Path $repo 'target/rag-local-fixes-20261024'
New-Item -ItemType Directory -Force -Path "$run/classes" | Out-Null
$savedJava = $env:JAVA_HOME; $savedPath = $env:Path
Push-Location $repo
try {
    $env:JAVA_HOME=$JavaHome; $env:Path="$JavaHome/bin;$env:Path"
    # 离线构建，不加载 Nacos、凭据或应用上下文。
    & mvn -s "$env:USERPROFILE/.m2/settings.xml" -o -pl tj-aigc -am compile dependency:build-classpath "-Dmdep.outputFile=$run/classpath.txt" *> "$run/replay-build.log"
    if ($LASTEXITCODE -ne 0) { throw "Offline replay build failed; see $run/replay-build.log" }
    $classpath = "$repo/tj-aigc/target/classes;$repo/tj-api/target/classes;$repo/tj-common/target/classes;" + [IO.File]::ReadAllText("$run/classpath.txt").Trim()
    $utf8 = [Text.UTF8Encoding]::new($false)
    [IO.File]::WriteAllLines("$run/javac-replay.args", @('-encoding','UTF-8','-cp',('"' + $classpath.Replace('\','/') + '"'),
            '-d',('"' + "$run/classes".Replace('\','/') + '"'),('"' + "$PSScriptRoot/RagCandidateReplay.java".Replace('\','/') + '"')), $utf8)
    & "$JavaHome/bin/javac.exe" "@$run/javac-replay.args" *> "$run/replay-compile.log"
    if ($LASTEXITCODE -ne 0) { Get-Content "$run/replay-compile.log"; throw 'Replay compilation failed' }
    $output = "$repo/docs/rag-evaluation/results/local-fixes-v2-replay.json"
    [IO.File]::WriteAllLines("$run/java-replay.args", @('-Dfile.encoding=UTF-8','-cp',('"' + "$run/classes;$classpath".Replace('\','/') + '"'),
            'RagCandidateReplay',('"' + $repo.Replace('\','/') + '"'),('"' + $output.Replace('\','/') + '"')), $utf8)
    & "$JavaHome/bin/java.exe" "@$run/java-replay.args" *> "$run/replay.log"
    if ($LASTEXITCODE -ne 0) { Get-Content "$run/replay.log" -Tail 20; throw 'Offline replay failed' }
    Get-Content "$run/replay.log" | Where-Object { $_ -match '^\{"control"' }
    Write-Output "Offline report: $output"
} finally {
    $env:JAVA_HOME=$savedJava; $env:Path=$savedPath
    Pop-Location
}
