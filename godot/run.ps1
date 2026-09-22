param(
    [string]$Godot = 'D:\software\godot\Godot_v4.6.2-stable_win64.exe\Godot_v4.6.2-stable_win64.exe',
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Save = '',
    [int]$Port = 17321,
    [switch]$SkipBuild,
    [switch]$Smoke,
    [switch]$Headless
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$local = Join-Path $PSScriptRoot '.local'
$previousJava = $env:JAVA_HOME
$previousToken = $env:UNCIV_GATEWAY_TOKEN
$previousPort = $env:UNCIV_GATEWAY_PORT
$previousSave = $env:UNCIV_INITIAL_SAVE
$kernel = $null
$exitCode = 0
try {
    if (-not $JavaHome) {
        $installedJava = Get-Command java.exe -ErrorAction SilentlyContinue
        if ($installedJava) { $JavaHome = Split-Path (Split-Path $installedJava.Source -Parent) -Parent }
        else {
            $candidate = Get-ChildItem "$env:USERPROFILE\.qoder\extensions\redhat.java-*\jre\*\bin\java.exe" -ErrorAction SilentlyContinue |
                Sort-Object FullName -Descending | Select-Object -First 1
            if ($candidate) { $JavaHome = Split-Path (Split-Path $candidate.FullName -Parent) -Parent }
        }
    }
    if (-not $JavaHome -or -not (Test-Path "$JavaHome\bin\java.exe")) {
        throw 'JDK 21+ required. Pass -JavaHome <JDK directory>.'
    }
    if (-not (Test-Path $Godot)) { throw "Godot executable not found: $Godot" }
    if ($Headless -and -not $Smoke) { throw '-Headless requires -Smoke.' }
    $env:JAVA_HOME = $JavaHome
    if (-not $SkipBuild) {
        & "$root\gradlew.bat" -p $root :godot-kernel:installDist :godot-kernel:test --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Kernel build/tests failed.' }
    }
    $libs = Join-Path $PSScriptRoot 'kernel\build\install\godot-kernel\lib\*'
    if (-not (Test-Path (Split-Path $libs -Parent))) { throw 'Build first: run.ps1 without -SkipBuild.' }
    New-Item -ItemType Directory -Force $local | Out-Null
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($bytes)
    $rng.Dispose()
    $env:UNCIV_GATEWAY_TOKEN = [Convert]::ToBase64String($bytes)
    $env:UNCIV_GATEWAY_PORT = [string]$Port
    $env:UNCIV_INITIAL_SAVE = if ($Save) { (Resolve-Path $Save).Path } else { '' }
    $kernel = Start-Process -FilePath "$JavaHome\bin\java.exe" -PassThru -WindowStyle Hidden `
        -WorkingDirectory "$root\android\assets" `
        -ArgumentList @('-Xmx2g', '-cp', "`"$libs`"", 'com.unciv.godot.GatewayMainKt', '--root', "`"$root`"", '--port', "$Port") `
        -RedirectStandardOutput "$local\kernel.log" -RedirectStandardError "$local\kernel-error.log"
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($kernel.HasExited) { throw "Kernel exited. See $local\kernel-error.log" }
        try {
            $hello = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api" -Method Post `
                -ContentType 'application/json' -Headers @{ Authorization = "Bearer $env:UNCIV_GATEWAY_TOKEN" } `
                -Body '{"protocol":1,"action":"hello"}' -TimeoutSec 2
            if ($hello.ok) { $ready = $true; break }
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $ready) { throw 'Kernel did not become ready; check .local/kernel-error.log.' }
    $godotArgs = @('--path', "`"$PSScriptRoot`"")
    if ($Headless) { $godotArgs += '--headless' }
    if ($Smoke) { $godotArgs += @('--', '--smoke') }
    $frontendStarted = Get-Date
    $frontend = Start-Process -FilePath $Godot -ArgumentList $godotArgs -Wait -PassThru `
        -RedirectStandardOutput "$local\godot.log" -RedirectStandardError "$local\godot-error.log"
    $exitCode = $frontend.ExitCode
    if ($Smoke) {
        $reportFile = Get-Item "$local\smoke-result.json" -ErrorAction SilentlyContinue
        if (-not $reportFile -or $reportFile.LastWriteTime -lt $frontendStarted) {
            throw 'Smoke 未生成本轮报告。'
        }
        $report = Get-Content $reportFile.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($report.runId -notmatch '^smoke-\d+$') { throw 'Smoke 运行标识无效。' }
        foreach ($name in @('godot.log', 'godot-error.log', 'kernel.log', 'kernel-error.log')) {
            Copy-Item "$local\$name" "$local\$($report.runId)-$name"
        }
        if ($exitCode -eq 0 -and -not $report.ok) { $exitCode = 1 }
        if ($exitCode -eq 0) { Write-Host "Smoke passed ($($report.runId)). Results: $local\smoke-result.json" }
    }
} finally {
    if ($kernel -and -not $kernel.HasExited) { Stop-Process -Id $kernel.Id }
    $env:JAVA_HOME = $previousJava
    $env:UNCIV_GATEWAY_TOKEN = $previousToken
    $env:UNCIV_GATEWAY_PORT = $previousPort
    $env:UNCIV_INITIAL_SAVE = $previousSave
}
exit $exitCode
