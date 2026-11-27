param([string]$MavenCommand)
$ErrorActionPreference='Stop'
$taskRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 21 or newer.' }
$env:Path=(Join-Path $env:JAVA_HOME 'bin')+';'+$env:Path
$modules=@('backend/identity-organization-service','backend/repository-integration-service','infrastructure/api-gateway')
foreach ($module in $modules) {
    $moduleRoot=Join-Path $taskRoot $module
    $buildOutput=Join-Path $moduleRoot 'target/liv151-e2e-build'
    $targetDirectory=Join-Path $moduleRoot 'target'
    [IO.Directory]::CreateDirectory($targetDirectory) | Out-Null
    $temporaryPom=Join-Path $targetDirectory 'liv151-e2e-pom.xml'
    $pom=[IO.File]::ReadAllText((Join-Path $moduleRoot 'pom.xml'))
    # Preserve production dependencies/plugins; isolate outputs to avoid locked local JARs.
    $buildSettings='<directory>'+($buildOutput.Replace('\','/'))+'</directory>'+
        '<sourceDirectory>'+(Join-Path $moduleRoot 'src/main/java').Replace('\','/')+'</sourceDirectory>'+
        '<resources><resource><directory>'+(Join-Path $moduleRoot 'src/main/resources').Replace('\','/')+'</directory></resource></resources>'
    $pom=$pom.Replace('<directory>${livingdocs.build.directory}</directory>','')
    $pom=$pom.Replace('<build>','<build>'+$buildSettings)
    [IO.File]::WriteAllText($temporaryPom,$pom,[Text.UTF8Encoding]::new($false))
    Push-Location $moduleRoot
    try {
        $executable=if ($MavenCommand) {$MavenCommand} else {Join-Path $moduleRoot 'mvnw.cmd'}
        & $executable -o -f $temporaryPom '-Dmaven.compiler.proc=full' '-Dmaven.test.skip=true' package
        if ($LASTEXITCODE -ne 0) {throw "Cannot build production fixture: $module"}
    } finally {Pop-Location}
}
