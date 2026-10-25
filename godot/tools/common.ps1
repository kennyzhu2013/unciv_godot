# 开发与发布共用的环境探测：Godot 可执行文件与 Java 21+。
# 由 run.ps1 与 tools/package_windows.ps1 dot-source；只返回值，不修改调用方的环境变量。

function Resolve-GodotExecutable {
    param([string]$Explicit)
    $candidates = New-Object System.Collections.Generic.List[string]
    if ($Explicit) { $candidates.Add($Explicit) }
    if ($env:GODOT_BIN) { $candidates.Add($env:GODOT_BIN) }
    $installed = Get-Command godot.exe, godot -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($installed) { $candidates.Add($installed.Source) }
    $roots = @(
        'D:\Program Files\bin',
        'C:\Program Files\bin',
        "$env:LOCALAPPDATA\Programs",
        "$env:USERPROFILE\scoop\apps",
        'D:\software'
    )
    foreach ($root in $roots) {
        if (-not $root -or -not (Test-Path $root)) { continue }
        $found = Get-ChildItem -Path $root -Filter 'Godot_v*-stable_win64.exe' -Recurse -Depth 2 -File -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending | Select-Object -First 1
        if ($found) { $candidates.Add($found.FullName) }
    }
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate -PathType Leaf)) { return (Resolve-Path $candidate).Path }
    }
    throw '未找到 Godot 4.x 可执行文件。请设置环境变量 GODOT_BIN、传入 -Godot <路径>，或将 godot 加入 PATH。'
}

function Get-JavaMajorVersion {
    param([string]$JavaExe)
    # java -version 写 stderr；在 $ErrorActionPreference='Stop' 下 2>&1 会被当成终止错误，需临时降级。
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $text = (& $JavaExe '-version' 2>&1 | Out-String) } finally { $ErrorActionPreference = $previous }
    if ($text -match 'version "(\d+)') { return [int]$Matches[1] }
    if ($text -match '(?m)^(?:openjdk|java) (\d+)') { return [int]$Matches[1] }
    return 0
}

function Resolve-JavaHome {
    param([string]$Explicit)
    $candidates = New-Object System.Collections.Generic.List[string]
    if ($Explicit) { $candidates.Add($Explicit) }
    if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }
    $installed = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($installed) { $candidates.Add((Split-Path (Split-Path $installed.Source -Parent) -Parent)) }
    $ide = Get-ChildItem "$env:USERPROFILE\.qoder\extensions\redhat.java-*\jre\*\bin\java.exe" -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if ($ide) { $candidates.Add((Split-Path (Split-Path $ide.FullName -Parent) -Parent)) }
    foreach ($root in @('D:\Program Files', 'C:\Program Files\Java', 'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Microsoft')) {
        if (-not (Test-Path $root)) { continue }
        foreach ($jdk in Get-ChildItem -Path $root -Directory -Filter 'jdk*' -ErrorAction SilentlyContinue) {
            $candidates.Add($jdk.FullName)
        }
    }
    foreach ($jdkHome in $candidates) {
        if (-not $jdkHome) { continue }
        $java = Join-Path $jdkHome 'bin\java.exe'
        if (-not (Test-Path $java -PathType Leaf)) { continue }
        if ((Get-JavaMajorVersion $java) -ge 21) { return (Resolve-Path $jdkHome).Path }
    }
    throw '未找到 Java 21+。请安装 JDK 21 或更高版本，或设置 JAVA_HOME / 传入 -JavaHome <目录>。'
}
