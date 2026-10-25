param(
    [switch]$SelfTest,
    [switch]$Visual,
    [int]$Port = 17321,
    [string]$JavaHome = $env:JAVA_HOME
)
# Unciv · Godot 发布包启动器：先起内核网关并等待 hello，再起前端；前端退出后回收内核。
# 用法：双击 UncivGodot.bat 正常游玩；start-unciv.ps1 -SelfTest 跑发布自检（headless）；加 -Visual 可带界面自检。
$ErrorActionPreference = 'Stop'
$dist = $PSScriptRoot
$logs = Join-Path $dist 'logs'
New-Item -ItemType Directory -Force $logs | Out-Null

$candidates = New-Object System.Collections.Generic.List[string]
if ($env:UNCIV_JAVA_HOME) { $candidates.Add($env:UNCIV_JAVA_HOME) }
if ($JavaHome) { $candidates.Add($JavaHome) }
$onPath = Get-Command java.exe -ErrorAction SilentlyContinue
if ($onPath) { $candidates.Add((Split-Path (Split-Path $onPath.Source -Parent) -Parent)) }
$java = $null
foreach ($jdk in $candidates) {
    if (-not $jdk) { continue }
    $javaExe = Join-Path $jdk 'bin\java.exe'
    if (-not (Test-Path $javaExe -PathType Leaf)) { continue }
    # java -version 写 stderr；在 Stop 策略下 2>&1 会被当成终止错误，临时降级后恢复。
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $version = (& $javaExe '-version' 2>&1 | Out-String) } finally { $ErrorActionPreference = $previous }
    $major = 0
    if ($version -match 'version "(\d+)') { $major = [int]$Matches[1] }
    elseif ($version -match '(?m)^(?:openjdk|java) (\d+)') { $major = [int]$Matches[1] }
    if ($major -ge 21) { $java = $javaExe; break }
}
if (-not $java) {
    Write-Host '需要 Java 21 或更高版本。请安装 JDK 21+ 并设置 JAVA_HOME（或 UNCIV_JAVA_HOME）指向 JDK 目录。' -ForegroundColor Red
    exit 2
}

$exe = Join-Path $dist 'UncivGodot.exe'
if (-not (Test-Path $exe -PathType Leaf)) { Write-Host "缺少前端可执行文件：$exe" -ForegroundColor Red; exit 2 }
$kernelAssets = Join-Path $dist 'kernel\assets'
if (-not (Test-Path (Join-Path $kernelAssets 'jsons') -PathType Container)) { Write-Host "缺少内核数据目录：$kernelAssets\jsons" -ForegroundColor Red; exit 2 }

# 端口被占用时顺延，避免与本机其它实例冲突。
$chosen = 0
for ($candidate = $Port; $candidate -lt $Port + 10; $candidate++) {
    $listener = $null
    try {
        $listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, $candidate)
        $listener.Start()
        $chosen = $candidate
        break
    } catch {
        continue
    } finally {
        if ($listener) { $listener.Stop() }
    }
}
if ($chosen -eq 0) { Write-Host "端口 $Port..$($Port + 9) 均被占用，无法启动内核。" -ForegroundColor Red; exit 2 }

$bytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes)
$rng.Dispose()
$token = [Convert]::ToBase64String($bytes)
$env:UNCIV_GATEWAY_TOKEN = $token
$env:UNCIV_GATEWAY_PORT = [string]$chosen

$kernel = $null
$front = $null
try {
    $kernel = Start-Process -FilePath $java -PassThru -WindowStyle Hidden `
        -WorkingDirectory $kernelAssets `
        -ArgumentList @('-Xmx2g', '-cp', "`"$(Join-Path $dist 'kernel\lib\*')`"", 'com.unciv.godot.GatewayMainKt',
            '--root', "`"$dist`"", '--port', "$chosen") `
        -RedirectStandardOutput "$logs\kernel.log" -RedirectStandardError "$logs\kernel-error.log"
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($kernel.HasExited) { break }
        try {
            $hello = Invoke-RestMethod -Uri "http://127.0.0.1:$chosen/api" -Method Post `
                -ContentType 'application/json' -Headers @{ Authorization = "Bearer $token" } `
                -Body '{"protocol":1,"action":"hello"}' -TimeoutSec 2
            if ($hello.ok) { $ready = $true; break }
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    if (-not $ready) {
        Write-Host '内核未能在 30 秒内就绪。' -ForegroundColor Red
        if (Test-Path "$logs\kernel-error.log") { Get-Content "$logs\kernel-error.log" -Tail 15 }
        exit 3
    }

    $launchArgs = @()
    if ($SelfTest) {
        if (-not $Visual) { $launchArgs += '--headless' }
        $launchArgs += @('--', '--selftest')
    }
    if ($SelfTest) {
        $front = Start-Process -FilePath $exe -ArgumentList $launchArgs -PassThru -Wait `
            -RedirectStandardOutput "$logs\selftest.log" -RedirectStandardError "$logs\selftest-error.log"
        Get-Content "$logs\selftest.log" -ErrorAction SilentlyContinue
        if (Test-Path "$logs\selftest-error.log") { Get-Content "$logs\selftest-error.log" -ErrorAction SilentlyContinue }
    } else {
        $front = Start-Process -FilePath $exe -ArgumentList $launchArgs -PassThru -Wait
    }
} finally {
    if ($kernel -and -not $kernel.HasExited) { Stop-Process -Id $kernel.Id -Force -ErrorAction SilentlyContinue }
}
if ($front) { exit $front.ExitCode }
exit 0
