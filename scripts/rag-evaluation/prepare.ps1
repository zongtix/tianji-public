param(
    [Parameter(Mandatory=$true)][string]$Output,
    [Parameter(Mandatory=$true)][ValidatePattern('^tj_aigc_eval_[a-z0-9_]+$')][string]$Index,
    [string]$JavaHome = 'D:/Java/jdk-17'
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$run = Join-Path $repo 'target/rag-optimization'
New-Item -ItemType Directory -Force -Path "$run/classes" | Out-Null
$savedJava = $env:JAVA_HOME
$savedPath = $env:Path
Push-Location $repo
try {
    $env:JAVA_HOME = $JavaHome
    $env:Path = "$JavaHome/bin;$env:Path"
    & mvn -s "$env:USERPROFILE/.m2/settings.xml" -o -pl tj-aigc -am compile dependency:build-classpath "-Dmdep.outputFile=$run/classpath.txt" *> "$run/prepare-classpath.log"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot build corpus classpath; see target/rag-optimization/prepare-classpath.log' }
    $classpath = "$repo/tj-aigc/target/classes;$repo/tj-api/target/classes;$repo/tj-common/target/classes;" + [IO.File]::ReadAllText("$run/classpath.txt").Trim()
    $utf8 = [Text.UTF8Encoding]::new($false)
    [IO.File]::WriteAllLines("$run/prepare-javac.args", @('-encoding','UTF-8','-cp',('"' + $classpath.Replace('\','/') + '"'),
            '-d',('"' + "$run/classes".Replace('\','/') + '"'),('"' + "$PSScriptRoot/RagCorpus.java".Replace('\','/') + '"')), $utf8)
    & "$JavaHome/bin/javac.exe" "@$run/prepare-javac.args"
    if ($LASTEXITCODE -ne 0) { throw 'Corpus preparation compilation failed' }
    [IO.File]::WriteAllLines("$run/prepare-java.args", @('-Dfile.encoding=UTF-8','-cp',('"' + "$run/classes;$classpath".Replace('\','/') + '"'),
            'RagCorpus',('"' + $repo.Replace('\','/') + '"'),('"' + $Output.Replace('\','/') + '"'),$Index), $utf8)
    & "$JavaHome/bin/java.exe" "@$run/prepare-java.args"
    if ($LASTEXITCODE -ne 0) { throw 'Corpus preparation failed' }
} finally {
    $env:JAVA_HOME = $savedJava
    $env:Path = $savedPath
    Pop-Location
}
