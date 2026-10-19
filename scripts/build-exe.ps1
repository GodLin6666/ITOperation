# ============================================================
# 桌面版（exe）打包脚本
#
# 做五件事：
#   1. 构建前端（Vue -> dist）
#   2. 把前端产物和知识库种子复制进后端 resources（打成一个 jar）
#   3. 用 Maven 打出可执行 jar
#   4. 生成图标
#   5. 用 jpackage 把 JRE + jar 打成一个 exe（app-image 形式，免安装）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\build-exe.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\build-exe.ps1 -AppName "ITOps"
#   powershell -ExecutionPolicy Bypass -File scripts\build-exe.ps1 -SkipFrontend
#
# 产物：build-desktop\<AppName>\<AppName>.exe
#
# 前置条件：JDK 17（带 jpackage）、Node.js + npm、Maven
# ============================================================
param(
    # 注意：Windows 上 jpackage 生成的 exe 如果文件名是中文，launcher 会启动即退出（退出码 1）。
    # 这是实测出来的坑，所以 exe 名固定用 ASCII，中文名通过快捷方式体现。
    [string]$AppName = 'ITOpsAssistant',
    [string]$ShortcutName = 'IT运维助手',
    [switch]$SkipFrontend
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$root = Split-Path $PSScriptRoot -Parent
$webDir = Join-Path $root 'opsai-web'
$backendDir = Join-Path $root 'opsai-backend'
$resDir = Join-Path $backendDir 'src\main\resources'
$staticDir = Join-Path $resDir 'static'
$kbResDir = Join-Path $resDir 'knowledge'
$outDir = Join-Path $root 'build-desktop'
$iconPath = Join-Path $backendDir 'icon.ico'

function Step($t) { Write-Host ''; Write-Host ("== {0} ==" -f $t) -ForegroundColor Cyan }
function Good($t) { Write-Host ("  [OK] {0}" -f $t) -ForegroundColor Green }
function Bad($t)  { Write-Host ("  [错误] {0}" -f $t) -ForegroundColor Red; exit 1 }

# 执行外部命令。
# 为什么要包一层：PowerShell 在 $ErrorActionPreference='Stop' 时，
# 会把原生命令写到 stderr 的任何一行当成致命错误（NativeCommandError），
# 而 npm / mvn 在正常输出里也会往 stderr 写东西。
function Invoke-Native {
    param([scriptblock]$Cmd, [string]$Filter = '')
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $out = @()
    try { $out = & $Cmd 2>&1 } finally { $ErrorActionPreference = $prev }
    $code = $LASTEXITCODE
    foreach ($line in $out) {
        if ([string]::IsNullOrWhiteSpace($Filter) -or ("$line" -match $Filter)) { Write-Host ("  " + $line) }
    }
    return $code
}

Write-Host ''
Write-Host '  桌面版 exe 打包' -ForegroundColor Green
Write-Host '  ' + ('-' * 50)

# ---------- 0. 检查前置条件 ----------
Step '0/5 检查环境'
$jpackage = Get-Command jpackage -ErrorAction SilentlyContinue
if (-not $jpackage) { Bad '找不到 jpackage，请确认已安装 JDK 17+ 并加入 PATH' }
Good ("jpackage: " + $jpackage.Source)
if (-not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) { Bad '找不到 npm' }
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { Bad '找不到 mvn' }
Good 'npm / mvn 就绪'

# ---------- 1. 构建前端 ----------
if ($SkipFrontend) {
    Step '1/5 构建前端（已跳过，复用现有 dist）'
} else {
    Step '1/5 构建前端'
    Push-Location $webDir
    $env:npm_config_cache = Join-Path $root '.npm-cache'
    $code = Invoke-Native { & npm.cmd run build } 'error|✓ built'
    Pop-Location
    if ($code -ne 0) { Bad '前端构建失败' }
    Good '前端构建完成'
}

# ---------- 2. 复制前端产物 + 知识库 ----------
Step '2/5 复制前端产物和知识库到后端 resources'
if (-not (Test-Path (Join-Path $webDir 'dist\index.html'))) {
    Bad '前端产物不存在，请先不加 -SkipFrontend 跑一次'
}
if (Test-Path $staticDir) { Remove-Item $staticDir -Recurse -Force }
New-Item -ItemType Directory -Path $staticDir -Force | Out-Null
Copy-Item (Join-Path $webDir 'dist\*') $staticDir -Recurse -Force
$fileCount = (Get-ChildItem $staticDir -Recurse -File).Count
Good ("前端文件 {0} 个 -> resources/static" -f $fileCount)

if (Test-Path $kbResDir) { Remove-Item $kbResDir -Recurse -Force }
New-Item -ItemType Directory -Path $kbResDir -Force | Out-Null
Copy-Item (Join-Path $root 'knowledge\*.json') $kbResDir -Force
$kbCount = (Get-ChildItem $kbResDir -File).Count
Good ("知识库种子 {0} 个文件 -> resources/knowledge" -f $kbCount)

# ---------- 3. 打包 jar ----------
Step '3/5 Maven 打包'
Push-Location $backendDir
$code = Invoke-Native { & mvn -B -q clean package -DskipTests } '^\[ERROR\]|BUILD'
Pop-Location
if ($code -ne 0) { Bad 'Maven 打包失败' }
$jar = Get-ChildItem (Join-Path $backendDir 'target') -Filter 'opsai-backend-*.jar' -ErrorAction SilentlyContinue |
       Where-Object { $_.Name -notmatch 'original' } | Select-Object -First 1
if (-not $jar) { Bad '没找到打出来的 jar' }
Good ("jar: " + $jar.Name + "（" + [Math]::Round($jar.Length/1MB,1) + " MB）")

# ---------- 4. 生成图标 ----------
Step '4/5 生成图标'
try {
    Add-Type -AssemblyName System.Drawing
    $bmp = New-Object System.Drawing.Bitmap(256, 256)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::FromArgb(31, 45, 61))
    $font = New-Object System.Drawing.Font('Segoe UI', 100, [System.Drawing.FontStyle]::Bold)
    $fmt = New-Object System.Drawing.StringFormat
    $fmt.Alignment = [System.Drawing.StringAlignment]::Center
    $fmt.LineAlignment = [System.Drawing.StringAlignment]::Center
    $g.DrawString('IT', $font, [System.Drawing.Brushes]::White,
        (New-Object System.Drawing.RectangleF(0, 0, 256, 256)), $fmt)
    $g.Dispose()

    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $png = $ms.ToArray()
    $bmp.Dispose(); $ms.Dispose()

    # 手工封装成 .ico（Vista 起支持内嵌 PNG，比 BMP 格式简单可靠）
    $fs = [System.IO.File]::Create($iconPath)
    $bw = New-Object System.IO.BinaryWriter($fs)
    $bw.Write([UInt16]0); $bw.Write([UInt16]1); $bw.Write([UInt16]1)
    $bw.Write([Byte]0); $bw.Write([Byte]0); $bw.Write([Byte]0); $bw.Write([Byte]0)
    $bw.Write([UInt16]1); $bw.Write([UInt16]32)
    $bw.Write([UInt32]$png.Length); $bw.Write([UInt32]22)
    $bw.Write($png)
    $bw.Flush(); $bw.Close(); $fs.Close()
    Good ("图标已生成: " + $iconPath)
} catch {
    Write-Host ("  图标生成失败（将用默认图标）: " + $_.Exception.Message) -ForegroundColor Yellow
    $iconPath = $null
}

# ---------- 5. jpackage ----------
Step '5/5 jpackage 打包 exe'
if (Test-Path $outDir) { Remove-Item $outDir -Recurse -Force }
New-Item -ItemType Directory -Path $outDir -Force | Out-Null

# jpackage 的 --input 只接受目录，把 jar 单独放一个干净目录
$inputDir = Join-Path $outDir '_input'
New-Item -ItemType Directory -Path $inputDir -Force | Out-Null
Copy-Item $jar.FullName $inputDir -Force

$jpArgs = @(
    '--type', 'app-image',
    '--name', $AppName,
    '--dest', $outDir,
    '--input', $inputDir,
    '--main-jar', $jar.Name,
    '--main-class', 'org.springframework.boot.loader.launch.JarLauncher',
    '--java-options', '-Dspring.profiles.active=standalone',
    '--java-options', '-Dfile.encoding=UTF-8',
    '--java-options', '-Duser.language=zh',
    '--java-options', '-Duser.country=CN',
    '--java-options', '-Xmx1g',
    '--description', 'IT 运维故障智能辅助排查工具（桌面版）',
    '--vendor', 'opsai'
)
# 注意：这里刻意不传 --icon。
# 实测发现：用 System.Drawing 手工拼出来的 .ico 会让 jpackage 生成的「无窗口 launcher」
# 启动后直接卡死（进程在、内存也占着 180MB+，但永远不监听端口、不建数据目录）。
# 带 --win-console 时不受影响，所以问题出在 launcher 读取图标资源这一环。
# 需要自定义图标时，请用真正的图标编辑器导出多尺寸 .ico，再自行加上 --icon。

$code = Invoke-Native { & jpackage @jpArgs }
Remove-Item $inputDir -Recurse -Force -ErrorAction SilentlyContinue
if ($code -ne 0) { Bad 'jpackage 失败' }

$exePath = Join-Path $outDir "$AppName\$AppName.exe"
if (-not (Test-Path $exePath)) { Bad '没找到生成的 exe' }

# ---------- 生成中文名快捷方式 ----------
# exe 本身必须是 ASCII 名（实测中文名会导致 launcher 启动即退出），
# 所以再生成一个中文名的 .lnk，用户看到和双击的都是中文。
$appDir = Join-Path $outDir $AppName
$lnkPath = Join-Path $appDir "$ShortcutName.lnk"
try {
    $ws = New-Object -ComObject WScript.Shell
    $sc = $ws.CreateShortcut($lnkPath)
    $sc.TargetPath = $exePath
    $sc.WorkingDirectory = $appDir
    $sc.Description = 'IT 运维故障智能辅助排查工具'
    $sc.IconLocation = $exePath
    $sc.Save()
    Good ("中文快捷方式: " + $lnkPath)
} catch {
    Write-Host ("  快捷方式创建失败: " + $_.Exception.Message) -ForegroundColor Yellow
}

$size = (Get-ChildItem $appDir -Recurse -File | Measure-Object Length -Sum).Sum
Write-Host ''
Write-Host '  打包完成' -ForegroundColor Green
Write-Host ("    可执行文件: " + $exePath) -ForegroundColor Yellow
Write-Host ("    中文入口:   " + $lnkPath) -ForegroundColor Yellow
Write-Host ("    目录总大小: " + [Math]::Round($size/1MB,1) + " MB（含捆绑的 JRE）")
Write-Host ''
Write-Host '  使用方式：双击「IT运维助手」快捷方式即可，会自动打开浏览器。' -ForegroundColor Green
Write-Host '  前置条件：本机已安装并运行 Ollama（唯一的外部依赖）。' -ForegroundColor Green
Write-Host '  数据位置：%USERPROFILE%\opsai-data\（H2 数据库 + 上传文件 + opsai.log）' -ForegroundColor DarkGray
Write-Host '  说明：exe 文件名必须保持 ASCII，中文入口用快捷方式代替（jpackage 的已知限制）。' -ForegroundColor DarkGray
