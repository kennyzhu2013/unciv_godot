param(
    [string]$Godot = $env:GODOT_BIN,
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$SkipBuild,
    [switch]$DownloadTemplates,
    [switch]$SkipTemplates,
    [switch]$Verify,
    [switch]$KeepDist
)
# T5 发布打包：把前端、素材、内核 jar 与启动器组装到 godot/dist。
# 导出优先原生 exe（需 export templates），缺失或失败时自动回退 Godot 二进制 + UncivGodot.pck。
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

$project = Split-Path $PSScriptRoot -Parent
$repo = Split-Path $project -Parent
$dist = Join-Path $project 'dist'
$local = Join-Path $project '.local'
$release = Join-Path $PSScriptRoot 'release'
$started = Get-Date
$context = [ordered]@{
    runId = 'package-' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    startedAt = $started.ToString('o')
    mode = $null
    templateFallbackReason = $null
    templateSource = $null
    godot = $null
    java = $null
    exeMB = $null
    pckMB = $null
    totalMB = $null
    verify = $null
    failure = $null
    finishedAt = $null
    durationSeconds = $null
}
$sections = [ordered]@{}

function Copy-Tree {
    param([string]$Source, [string]$Destination)
    if (-not (Test-Path $Source)) { throw "缺少素材目录：$Source" }
    New-Item -ItemType Directory -Force $Destination | Out-Null
    Copy-Item -Path (Join-Path $Source '*') -Destination $Destination -Recurse -Force
    $stats = Get-ChildItem $Destination -Recurse -File | Measure-Object Length -Sum
    return [ordered]@{ files = $stats.Count; mb = [math]::Round($stats.Sum / 1MB, 1) }
}

function Wait-ForFile {
    param([string]$Path, [int]$Seconds = 15)
    for ($i = 0; $i -lt $Seconds * 4; $i++) {
        if (Test-Path $Path) { return $true }
        Start-Sleep -Milliseconds 250
    }
    return $false
}

# 长耗时导出用 Start-Process 同步等待：直接用 & 调用时 PowerShell 可能在文件写完前就返回。
# Start-Process 不做参数转义，含空格的参数（如导出预设名）必须自行加引号。
function Invoke-Godot {
    param([string]$Godot, [string[]]$Arguments, [string]$LogName)
    $out = Join-Path $local "$LogName.log"
    $err = Join-Path $local "$LogName.err.log"
    $line = ($Arguments | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '
    $process = Start-Process -FilePath $Godot -ArgumentList $line -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput $out -RedirectStandardError $err
    return [ordered]@{ code = $process.ExitCode; log = $out; stderr = $err }
}

function Get-TemplateInfo {
    param([string]$Godot)
    $raw = (& $Godot --version | Out-String).Trim()
    if ($raw -notmatch '^(\d+\.\d+\.\d+)\.stable') { throw "无法识别 Godot 版本：$raw" }
    $tag = $Matches[1]
    $dir = Join-Path $env:APPDATA "Godot\export_templates\$tag.stable"
    return [ordered]@{ tag = $tag; dir = $dir; file = (Join-Path $dir 'windows_release_x86_64.exe') }
}

# 大文件走流式下载：WebClient 会沿用系统代理且不占内存；curl 不读系统代理，仅作回退。
function Invoke-Download {
    param([string]$Url, [string]$OutFile)
    try {
        $client = New-Object System.Net.WebClient
        $client.Proxy = [System.Net.WebRequest]::DefaultWebProxy
        if ($client.Proxy) { $client.Proxy.Credentials = [System.Net.CredentialCache]::DefaultCredentials }
        $client.Headers.Add('User-Agent', 'UncivGodotPackager/1.0')
        $client.DownloadFile($Url, $OutFile)
        $client.Dispose()
        return $true
    } catch {
        Write-Host "下载失败（WebClient）：$($_.Exception.Message)"
    }
    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    if ($curl) {
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try { & $curl.Source '-sS' '-L' '--fail' '--retry' '2' '-o' $OutFile $Url } finally { $ErrorActionPreference = $previous }
        return ($LASTEXITCODE -eq 0)
    }
    return $false
}

# 仅在显式 -DownloadTemplates 时联网；校验以 release 的 SHA512-SUMS.txt 为准，取不到只做体积检查并记录。
function Install-ExportTemplates {
    param([string]$Godot)
    $info = Get-TemplateInfo $Godot
    if (Test-Path $info.file) { return $info }
    $tag = $info.tag
    $base = "https://github.com/godotengine/godot-builds/releases/download/$tag-stable"
    $archive = Join-Path $env:TEMP "Godot_v$tag-stable_export_templates.tpz"
    Write-Host "下载导出模板：$base/Godot_v$tag-stable_export_templates.tpz"
    if (-not (Invoke-Download "$base/Godot_v$tag-stable_export_templates.tpz" $archive)) { throw '导出模板下载失败' }
    if ((Get-Item $archive).Length -lt 500MB) { throw "导出模板体积异常（$([math]::Round((Get-Item $archive).Length / 1MB, 1)) MB）" }
    try {
        $sums = "$archive.sums.txt"
        if (-not (Invoke-Download "$base/SHA512-SUMS.txt" $sums)) { throw 'SHA512 校验清单不可用' }
        $line = Select-String -Path $sums -Pattern 'export_templates\.tpz' | Select-Object -First 1
        if ($line) {
            $expected = ($line.Line -split '\s+')[0]
            $actual = (Get-FileHash $archive -Algorithm SHA512).Hash.ToLower()
            if ($actual -ne $expected.ToLower()) { throw '导出模板 SHA512 校验失败' }
            $context.templateSource = 'sha512-verified'
        } else {
            $context.templateSource = 'size-check-only'
        }
        Remove-Item $sums -Force -ErrorAction SilentlyContinue
    } catch {
        if ($_.Exception.Message -like '*SHA512 校验失败*') { throw }
        $context.templateSource = 'size-check-only'
    }
    $extract = Join-Path $env:TEMP "godot-templates-$tag"
    $zip = "$archive.zip"
    if (Test-Path $extract) { Remove-Item -Recurse -Force $extract }
    Copy-Item $archive $zip -Force
    Expand-Archive -Path $zip -DestinationPath $extract -Force
    $templates = Join-Path $extract 'templates'
    if (-not (Test-Path $templates)) { throw '导出模板包结构异常：缺少 templates/' }
    New-Item -ItemType Directory -Force $info.dir | Out-Null
    Copy-Item -Path (Join-Path $templates '*') -Destination $info.dir -Recurse -Force
    Remove-Item -Recurse -Force $extract -ErrorAction SilentlyContinue
    Remove-Item -Force $archive, $zip -ErrorAction SilentlyContinue
    if (-not (Test-Path $info.file)) { throw '导出模板安装后仍缺少 windows_release_x86_64.exe' }
    return $info
}

try {
    $Godot = Resolve-GodotExecutable -Explicit $Godot
    $JavaHome = Resolve-JavaHome -Explicit $JavaHome
    $context.godot = $Godot
    $context.java = $JavaHome
    if (-not (Test-Path $release)) { throw "缺少发布模板目录：$release" }

    if (-not $SkipBuild) {
        & (Join-Path $repo 'gradlew.bat') '-p' $repo ':godot-kernel:installDist' '--console=plain'
        if ($LASTEXITCODE -ne 0) { throw '内核构建失败：:godot-kernel:installDist' }
    }
    $kernelLib = Join-Path $project 'kernel\build\install\godot-kernel\lib'
    if (-not (Test-Path $kernelLib)) { throw "缺少内核产物：$kernelLib（请先不带 -SkipBuild 运行一次）" }

    New-Item -ItemType Directory -Force $local | Out-Null
    if ((Test-Path $dist) -and -not $KeepDist) { Remove-Item -Recurse -Force $dist }
    New-Item -ItemType Directory -Force $dist | Out-Null
    # dist 在 res:// 内；.gdignore 让 Godot 不去扫描/导入打包产物（否则会把上千张 PNG 拖进导入管线）。
    New-Item -ItemType File -Force (Join-Path $dist '.gdignore') | Out-Null
    $sections['images_tilesets'] = Copy-Tree (Join-Path $repo 'android\Images.Tilesets') (Join-Path $dist 'assets\Images.Tilesets')
    $sections['images_units'] = Copy-Tree (Join-Path $repo 'android\Images.AbsoluteUnits') (Join-Path $dist 'assets\Images.AbsoluteUnits')
    $sections['sounds'] = Copy-Tree (Join-Path $repo 'android\assets\sounds') (Join-Path $dist 'assets\sounds')
    $sections['kernel_lib'] = Copy-Tree $kernelLib (Join-Path $dist 'kernel\lib')
    foreach ($name in @('jsons', 'effects', 'ExtraImages')) {
        $sections["kernel_$name"] = Copy-Tree (Join-Path $repo "android\assets\$name") (Join-Path $dist "kernel\assets\$name")
    }

    if ($DownloadTemplates -and $SkipTemplates) { throw '-DownloadTemplates 与 -SkipTemplates 不能同时使用。' }
    $templateReady = $false
    if ($DownloadTemplates) {
        try {
            $info = Install-ExportTemplates $Godot
            $templateReady = Test-Path $info.file
            if (-not $templateReady) { $context.templateFallbackReason = '导出模板缺失' }
        } catch {
            $context.templateFallbackReason = '导出模板获取失败：' + $_.Exception.Message
        }
    } else {
        $info = Get-TemplateInfo $Godot
        if (Test-Path $info.file) { $templateReady = $true; $context.templateSource = 'preinstalled' }
        else { $context.templateFallbackReason = '未安装导出模板（未传 -DownloadTemplates）' }
    }

    $exe = Join-Path $dist 'UncivGodot.exe'
    $pck = Join-Path $dist 'UncivGodot.pck'
    Remove-Item $exe, $pck -Force -ErrorAction SilentlyContinue

    Write-Host '导入项目资源…'
    $import = Invoke-Godot $Godot @('--headless', '--path', $project, '--import') 'import'
    if ($import.code -ne 0) { Write-Host "导入返回 $($import.code)，继续尝试导出。" }

    $native = $false
    if ($templateReady) {
        Write-Host '原生导出：Windows Desktop'
        $result = Invoke-Godot $Godot @('--headless', '--path', $project, '--export-release', 'Windows Desktop', $exe) 'export-native'
        if ($result.code -eq 0 -and (Wait-ForFile $exe) -and (Get-Item $exe).Length -gt 1MB) {
            $native = $true
            $context.mode = 'native'
        } else {
            $context.templateFallbackReason = "原生导出失败（exit=$($result.code)）"
            Remove-Item $exe -Force -ErrorAction SilentlyContinue
        }
    }
    if (-not $native) {
        Write-Host '回退导出：UncivGodot.pck + Godot 二进制'
        $result = Invoke-Godot $Godot @('--headless', '--path', $project, '--export-pack', 'Windows Desktop', $pck) 'export-pack'
        if (-not (Wait-ForFile $pck) -or (Get-Item $pck).Length -lt 100KB) {
            Get-Content $result.stderr -ErrorAction SilentlyContinue | Select-Object -First 8
            throw "回退打包失败（exit=$($result.code)，日志 $($result.log)）"
        }
        Copy-Item $Godot $exe -Force
        $context.mode = 'pck'
        $context.pckMB = [math]::Round((Get-Item $pck).Length / 1MB, 1)
    }

    Copy-Item (Join-Path $release 'start-unciv.ps1') $dist -Force
    Copy-Item (Join-Path $release 'UncivGodot.bat') $dist -Force
    Copy-Item (Join-Path $release 'README.txt') $dist -Force
    $banner = "`r`n构建信息`r`n--------`r`n导出方式：" +
        $(if ($native) { '原生导出（Godot export templates）' } else { 'PCK 便携回退（Godot 二进制 + UncivGodot.pck）' }) +
        "`r`n构建时间：" + $started.ToString('yyyy-MM-dd HH:mm') +
        "`r`n内核 Java：" + (Get-Content (Join-Path $JavaHome 'release') -ErrorAction SilentlyContinue | Select-String 'JAVA_VERSION' | ForEach-Object { ($_ -split '=')[1].Trim('"') }) +
        "`r`n"
    [System.IO.File]::AppendAllText((Join-Path $dist 'README.txt'), $banner, (New-Object System.Text.UTF8Encoding($false)))

    $context.exeMB = [math]::Round((Get-Item $exe).Length / 1MB, 1)
    $stats = Get-ChildItem $dist -Recurse -File | Measure-Object Length -Sum
    $context.totalMB = [math]::Round($stats.Sum / 1MB, 1)
    Write-Host "打包完成：mode=$($context.mode) 总体积 $($context.totalMB) MB"

    if ($Verify) {
        Write-Host '发布自检…'
        $launcher = Join-Path $dist 'start-unciv.ps1'
        $verifyLog = Join-Path $local 'package-verify.log'
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try { & powershell -NoProfile -ExecutionPolicy Bypass -File $launcher -SelfTest -JavaHome $JavaHome *> $verifyLog } finally { $ErrorActionPreference = $previous }
        $code = $LASTEXITCODE
        Get-Content $verifyLog -ErrorAction SilentlyContinue | Select-Object -Last 20
        $log = Join-Path $dist 'logs\selftest.log'
        $text = if (Test-Path $log) { Get-Content $log -Raw } else { '' }
        $ok = ($code -eq 0) -and ($text -match 'SELFTEST OK')
        $context.verify = [ordered]@{ exitCode = $code; ok = $ok; log = $log }
        if (-not $ok) { throw "发布自检失败（exit=$code），见 $log" }
        Write-Host '发布自检通过：SELFTEST OK'
    }
} catch {
    $context.failure = $_.Exception.Message
    throw
} finally {
    $context.finishedAt = (Get-Date).ToString('o')
    $context.durationSeconds = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
    $context['sections'] = $sections
    try {
        if (-not (Test-Path $local)) { New-Item -ItemType Directory -Force $local | Out-Null }
        $json = $context | ConvertTo-Json -Depth 6
        [System.IO.File]::WriteAllText((Join-Path $local 'package-latest.json'), $json, (New-Object System.Text.UTF8Encoding($false)))
    } catch {
        Write-Host "打包摘要写入失败：$($_.Exception.Message)"
    }
}
