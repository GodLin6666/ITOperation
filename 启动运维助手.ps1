# ============================================================
# 一键启动器：双击桌面「启动运维助手.bat」会调用本脚本
# 做四件事：启动 Docker Desktop → 启动 Ollama → 启动容器 → 打开浏览器
# 全程自动等待，哪一步卡住会明确提示，不会静默失败。
#
# 用法：
#   powershell -File 启动运维助手.ps1              打开登录页
#   powershell -File 启动运维助手.ps1 -AutoLogin   直接以只读用户身份进问答页
# ============================================================
param([switch]$AutoLogin)

$ErrorActionPreference = 'SilentlyContinue'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$webUrl    = 'http://localhost:8000'
$loginUrl  = 'http://localhost:8000/login?u=viewer&p=viewer123'
$healthUrl = 'http://localhost:8080/api/health'

function Step($t) { Write-Host ''; Write-Host ("== {0} ==" -f $t) -ForegroundColor Cyan }
function Good($t) { Write-Host ("  [OK] {0}" -f $t) -ForegroundColor Green }
function Bad($t)  { Write-Host ("  [错误] {0}" -f $t) -ForegroundColor Red }
function Wait($t) { Write-Host ("  ... {0}" -f $t) -ForegroundColor DarkGray }

Write-Host ''
Write-Host '  本地 IT 运维故障智能辅助排查工具 —— 一键启动' -ForegroundColor Green
Write-Host '  ' + ('-' * 46)

# ---------- 1/4 Docker ----------
Step '1/4 启动 Docker Desktop'

$docker = $null
$c = Get-Command docker -ErrorAction SilentlyContinue
if ($c) { $docker = $c.Source }
else {
    foreach ($p in @('E:\docker\resources\bin\docker.exe',
                     'C:\Program Files\Docker\Docker\resources\bin\docker.exe',
                     "$env:ProgramFiles\Docker\Docker\resources\bin\docker.exe")) {
        if (Test-Path $p) { $docker = $p; break }
    }
}
if (-not $docker) {
    Bad '没找到 Docker，请先安装 Docker Desktop'
    Read-Host '  按回车退出'; exit 1
}

& $docker info *> $null
if ($LASTEXITCODE -ne 0) {
    Wait 'Docker 引擎未运行，正在启动 Docker Desktop…'
    $dd = 'E:\docker\Docker Desktop.exe'
    if (-not (Test-Path $dd)) { $dd = 'C:\Program Files\Docker\Docker\Docker Desktop.exe' }
    if (Test-Path $dd) { Start-Process $dd }
    $ok = $false
    for ($i = 0; $i -lt 120; $i++) {
        Start-Sleep -Seconds 3
        & $docker info *> $null
        if ($LASTEXITCODE -eq 0) { $ok = $true; break }
    }
    if (-not $ok) { Bad 'Docker 引擎迟迟没起来，请手动打开 Docker Desktop 等鲸鱼图标变绿'; Read-Host '  按回车退出'; exit 1 }
}
Good 'Docker 引擎就绪'

# ---------- 2/4 Ollama ----------
Step '2/4 启动 Ollama'

# 确保模型目录设置生效（模型已搬到 E 盘，见 OLLAMA_MODELS）
$m = [Environment]::GetEnvironmentVariable('OLLAMA_MODELS', 'User')
if (-not $m) { $m = [Environment]::GetEnvironmentVariable('OLLAMA_MODELS', 'Machine') }
if ($m) { $env:OLLAMA_MODELS = $m }

$ollamaExe = "$env:LOCALAPPDATA\Programs\Ollama\ollama.exe"

# 只探测端口还不够：托盘程序有时不会把 OLLAMA_MODELS 传给服务子进程，
# 结果是端口通的但模型是空的。所以起来后还要确认"模型真的读到了"。
$up = Test-NetConnection -ComputerName localhost -Port 11434 -InformationLevel Quiet -WarningAction SilentlyContinue
if (-not $up) {
    Wait 'Ollama 未运行，正在启动…'
    if (Test-Path $ollamaExe) { Start-Process $ollamaExe -ArgumentList 'serve' -WindowStyle Hidden }
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Seconds 3
        if (Test-NetConnection -ComputerName localhost -Port 11434 -InformationLevel Quiet -WarningAction SilentlyContinue) { $up = $true; break }
    }
    if (-not $up) { Bad 'Ollama 没起来，请从开始菜单手动打开 Ollama'; Read-Host '  按回车退出'; exit 1 }
}

# 读模型列表，最多试 3 次；读不到就杀掉服务进程、用 serve 重新拉起
$models = @()
for ($try = 1; $try -le 3; $try++) {
    try { $models = @((Invoke-RestMethod 'http://localhost:11434/api/tags' -TimeoutSec 10).models | ForEach-Object { $_.name }) } catch {}
    if ($models.Count -gt 0) { break }
    Wait ('没读到模型（第 {0} 次），重启 Ollama 服务…' -f $try)
    & taskkill.exe /IM "ollama.exe" /F /T 2>&1 | Out-Null
    & taskkill.exe /IM "ollama app.exe" /F /T 2>&1 | Out-Null
    Start-Sleep -Seconds 3
    if (Test-Path $ollamaExe) { Start-Process $ollamaExe -ArgumentList 'serve' -WindowStyle Hidden }
    Start-Sleep -Seconds 8
}
if ($models.Count -gt 0) {
    Good 'Ollama 就绪'
    Good ('模型：' + ($models -join '、'))
} else {
    Bad 'Ollama 反复启动都读不到模型（OLLAMA_MODELS 可能没生效），诊断功能可能不可用'
    Bad ('当前 OLLAMA_MODELS = ' + $env:OLLAMA_MODELS)
}

# ---------- 3/4 容器 ----------
Step '3/4 启动容器'
$root = $PSScriptRoot
Set-Location $root
Wait 'docker compose up -d …'
& $docker compose up -d 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    Wait '容器不存在或需要重建，改为 up -d --build（首次可能要几分钟）…'
    & $docker compose up -d --build 2>&1 | Out-Null
}
Good '容器已启动（5 个）'

# ---------- 4/4 等后端 + 开浏览器 ----------
Step '4/4 等待服务就绪'
$ok = $false
for ($i = 0; $i -lt 80; $i++) {
    Start-Sleep -Seconds 3
    try { $h = Invoke-RestMethod $healthUrl -TimeoutSec 5; if ($h.status -eq 'ok') { $ok = $true; break } } catch {}
}

Write-Host ''
if ($ok) {
    Good '后端已就绪'
    Write-Host ''
    $target = if ($AutoLogin) { $loginUrl } else { $webUrl }
    Write-Host '  系统已启动！浏览器将自动打开：' -ForegroundColor Green
    Write-Host ("    " + $target) -ForegroundColor Yellow
    if ($AutoLogin) {
        Write-Host '  已配置为只读用户免登录，直接进入问答页。' -ForegroundColor Green
    } else {
        Write-Host '  登录账号：admin / admin123（管理员）  viewer / viewer123（只读）' -ForegroundColor Green
    }
    Write-Host ''
    Start-Process $target
} else {
    Wait '后端 4 分钟内没就绪，请稍后手动打开 http://localhost:8000'
    Wait '排查日志：docker compose logs -f backend'
}

Write-Host '  提示：本窗口可关闭，服务会在后台持续运行。' -ForegroundColor DarkGray
Write-Host ''
