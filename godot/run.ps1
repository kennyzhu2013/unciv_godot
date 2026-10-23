param(
    [string]$Godot = $env:GODOT_BIN,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Save = '',
    [int]$Port = 17321,
    [switch]$SkipBuild,
    [switch]$Smoke,
    [switch]$Headless,
    [ValidateRange(1, 3600)]
    [int]$SmokeTimeoutSeconds = 1200
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$local = Join-Path $PSScriptRoot '.local'
$previousJava = $env:JAVA_HOME
$previousToken = $env:UNCIV_GATEWAY_TOKEN
$previousPort = $env:UNCIV_GATEWAY_PORT
$previousSave = $env:UNCIV_INITIAL_SAVE
$previousSmoke = $env:UNCIV_SMOKE
$previousRunId = $env:UNCIV_SMOKE_RUN_ID
$kernel = $null
$frontend = $null
$frontendStarted = $null
$report = $null
$exitCode = 0
$runStarted = Get-Date
$context = [ordered]@{
    runId = 'smoke-' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    commit = $null
    dirty = $null
    mode = $(if ($Headless) { 'headless' } else { 'graphical' })
    buildRequested = -not [bool]$SkipBuild
    kernelTests = $null
    frontendExitCode = $null
    godotVersion = $null
    javaVersion = $null
}
try {
    if (-not $Godot) {
        $installedGodot = Get-Command godot.exe, godot -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($installedGodot) { $Godot = $installedGodot.Source }
        else { $Godot = 'D:\software\godot\Godot_v4.6.2-stable_win64.exe\Godot_v4.6.2-stable_win64.exe' }
    }
    if ($Smoke) {
        New-Item -ItemType Directory -Force $local | Out-Null
        $context.commit = (git -C $root rev-parse HEAD | Out-String).Trim()
        if ($LASTEXITCODE -ne 0) { throw 'Cannot identify source commit.' }
        $status = @(git -C $root status --porcelain)
        if ($LASTEXITCODE -ne 0) { throw 'Cannot identify working tree state.' }
        $context.dirty = $status.Count -gt 0
    }
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
    if (-not (Test-Path $Godot -PathType Leaf)) { throw "Godot executable not found: $Godot. Pass -Godot or set GODOT_BIN." }
    if ($Headless -and -not $Smoke) { throw '-Headless requires -Smoke.' }
    $env:JAVA_HOME = $JavaHome
    if ($Smoke) {
        $context.godotVersion = (& $Godot --version | Out-String).Trim()
        if ($LASTEXITCODE -ne 0) { throw 'Cannot query Godot version.' }
        $context.javaVersion = (& "$JavaHome\bin\java.exe" --version | Out-String).Trim()
        if ($LASTEXITCODE -ne 0) { throw 'Cannot query Java version.' }
    }
    if (-not $SkipBuild) {
        $buildArgs = @('-p', $root, ':godot-kernel:installDist', ':godot-kernel:test')
        # 仅强制重跑测试任务，场景与 XML 报告必须来自本轮；编译仍使用 Gradle 增量缓存。
        if ($Smoke) {
            $buildArgs += @('--rerun', '--console=plain')
            & "$root\gradlew.bat" @buildArgs | Tee-Object -FilePath "$local\$($context.runId)-build.log"
        } else {
            & "$root\gradlew.bat" @buildArgs --console=plain
        }
        if ($LASTEXITCODE -ne 0) { throw 'Kernel build/tests failed.' }
        if ($Smoke) {
            $summary = [ordered]@{ suites = 0; tests = 0; failures = 0; errors = 0; skipped = 0 }
            foreach ($xml in Get-ChildItem "$PSScriptRoot\kernel\build\test-results\test\TEST-*.xml") {
                if ($xml.LastWriteTime -lt $runStarted) { throw 'Kernel test report is stale.' }
                $suite = ([xml](Get-Content $xml.FullName -Raw -Encoding UTF8)).testsuite
                $summary.suites++
                foreach ($key in @('tests', 'failures', 'errors', 'skipped')) { $summary[$key] += [int]$suite.$key }
            }
            $context.kernelTests = $summary
            if ($summary.tests -eq 0 -or $summary.failures -gt 0 -or $summary.errors -gt 0) {
                throw 'Kernel test report is empty or contains failures.'
            }
        }
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
    # 仅冒烟运行开启内核 debugInjectEvent 门控（真实游玩/交互窗口不设此变量，该命令等同不存在）。
    $env:UNCIV_SMOKE = if ($Smoke) { '1' } else { '' }
    $env:UNCIV_SMOKE_RUN_ID = if ($Smoke) { $context.runId } else { '' }
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
    $frontend = Start-Process -FilePath $Godot -ArgumentList $godotArgs -PassThru `
        -RedirectStandardOutput "$local\godot.log" -RedirectStandardError "$local\godot-error.log"
    # Windows PowerShell 重定向日志后须提前保留句柄，否则退出后的 ExitCode 可能为空。
    $null = $frontend.Handle
    # 外层看门狗也覆盖脚本解析失败、尚未进入 smoke.run 的挂起。
    if ($Smoke -and -not $frontend.WaitForExit($SmokeTimeoutSeconds * 1000)) {
        throw 'Smoke frontend timed out.'
    }
    $frontend.WaitForExit()
    $frontend.Refresh()
    $exitCode = $frontend.ExitCode
    $context.frontendExitCode = $exitCode
    if ($null -eq $exitCode) { throw 'Cannot read frontend exit code.' }
    if ($Smoke) {
        $reportFile = Get-Item "$local\smoke-result.json" -ErrorAction SilentlyContinue
        if (-not $reportFile -or $reportFile.LastWriteTime -lt $frontendStarted) {
            throw 'Smoke 未生成本轮报告。'
        }
        $report = Get-Content $reportFile.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($report.runId -ne $context.runId) { throw 'Smoke report belongs to another run.' }
        if (($report.renderBackend -eq 'headless') -ne [bool]$Headless) { throw 'Smoke render mode mismatch.' }
        if ($exitCode -ne 0) { throw "Smoke frontend exited with code $exitCode; see the archived report." }
        if (-not $report.ok) { throw 'Smoke assertions failed; see the archived report.' }
        if (-not $Headless -and @($report.screenshots).Count -eq 0) { throw 'Graphical smoke did not capture screenshots.' }
        if (Select-String -Path "$local\godot.log", "$local\godot-error.log" -Pattern '^(SCRIPT ERROR:|ERROR:)') {
            throw 'Godot reported runtime errors despite completing smoke.'
        }
        Write-Host "Smoke passed ($($report.runId)). Results: $local\smoke-latest-$($context.mode).json"
    }
} catch {
    $exitCode = 1
    Write-Warning $_.Exception.Message
    if ($Smoke) {
        if ($null -eq $report -or $report.runId -ne $context.runId) {
            $report = [pscustomobject]@{ ok = $false; runId = $context.runId; startedAt = $runStarted.ToUniversalTime().ToString('o'); screenshots = @() }
        }
        $report.ok = $false
        $report | Add-Member -NotePropertyName launcherError -NotePropertyValue $_.Exception.Message -Force
    }
} finally {
    # 先恢复调用者环境；归档写入失败也不能遗留本次令牌或运行标识。
    $env:JAVA_HOME = $previousJava
    $env:UNCIV_GATEWAY_TOKEN = $previousToken
    $env:UNCIV_GATEWAY_PORT = $previousPort
    $env:UNCIV_INITIAL_SAVE = $previousSave
    $env:UNCIV_SMOKE = $previousSmoke
    $env:UNCIV_SMOKE_RUN_ID = $previousRunId
    if ($frontend -and -not $frontend.HasExited) { Stop-Process -Id $frontend.Id }
    if ($kernel -and -not $kernel.HasExited) { Stop-Process -Id $kernel.Id }
    if ($Smoke -and $null -ne $report) {
        # 成功和失败都归档；双模式各保留一份最新报告，不能用上次成功掩盖本次失败。
        $report | Add-Member -NotePropertyName verification -NotePropertyValue $context -Force
        $report | Add-Member -NotePropertyName launcherFinishedAt -NotePropertyValue ([DateTime]::UtcNow.ToString('o')) -Force
        $json = $report | ConvertTo-Json -Depth 100
        foreach ($name in @('smoke-result.json', "$($context.runId).json", "smoke-latest-$($context.mode).json")) {
            $json | Set-Content -Path (Join-Path $local $name) -Encoding UTF8
        }
        if ($null -ne $kernel -or $null -ne $frontend) {
            foreach ($name in @('godot.log', 'godot-error.log', 'kernel.log', 'kernel-error.log')) {
                $log = Get-Item "$local\$name" -ErrorAction SilentlyContinue
                if ($log -and $log.LastWriteTime -ge $runStarted) { Copy-Item $log.FullName "$local\$($context.runId)-$name" }
            }
        }
    }
}
exit $exitCode
