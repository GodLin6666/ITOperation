# ============================================================
# Docker 一键启动脚本（带启动前体检）
#
# 用法（在 opsai 目录下）：
#   powershell -ExecutionPolicy Bypass -File deploy\up.ps1
#
# 它会先检查 Docker、端口占用、Ollama 连通性，
# 有问题当场告诉你并给出处理办法，然后再执行 docker compose up。
# ============================================================
param(
    [switch]$SkipBuild,     # 只启动，不重新构建镜像
    [switch]$Force          # 端口被占用时自动结束占用进程（会先确认）
)

$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

function Head($t) { Write-Host "`n=== $t ===" -ForegroundColor Cyan }
function Good($t) { Write-Host "  [OK] $t" -ForegroundColor Green }
function Bad($t)  { Write-Host "  [X ] $t" -ForegroundColor Red }
function Warn($t) { Write-Host "  [!] $t" -ForegroundColor Yellow }

$fatal = $false

# ------------------------------------------------------------
Head '1. Docker 是否就绪'
# ------------------------------------------------------------
$dockerExe = $null
$d = Get-Command docker -ErrorAction SilentlyContinue
if ($d) { $dockerExe = $d.Source }

if (-not $dockerExe) {
    # PATH 可能还没刷新（刚装完 Docker 但没重开终端/没重启），到常见安装位置再找一遍。
    # 本机是装到 E:\docker 的，所以第一项就是它。
    foreach ($p in @(
        'E:\docker\resources\bin\docker.exe',
        'C:\Program Files\Docker\Docker\resources\bin\docker.exe',
        "$env:ProgramFiles\Docker\Docker\resources\bin\docker.exe"
    )) {
        if (Test-Path $p) { $dockerExe = $p; break }
    }
}

if (-not $dockerExe) {
    Bad '找不到 docker 命令，Docker Desktop 还没装'
    Write-Host '      请先按 deploy\Docker部署指引.md 完成安装（需要管理员权限 + 重启）'
    exit 1
}
Good "docker 命令位置：$dockerExe"

& $dockerExe info *> $null
if ($LASTEXITCODE -ne 0) {
    Bad 'Docker 守护进程没在运行（Docker Desktop 没启动？）'
    Write-Host '      请从开始菜单启动 Docker Desktop，等右下角鲸鱼图标变绿后重试'
    exit 1
}
$ver = (& $dockerExe version --format '{{.Server.Version}}' 2>$null)
Good "Docker 守护进程正常，引擎版本 $ver"

& $dockerExe compose version *> $null
if ($LASTEXITCODE -ne 0) {
    Bad 'docker compose（v2 插件）不可用'
    exit 1
}
Good "docker compose 可用"

# ------------------------------------------------------------
Head '2. 端口占用检查'
# ------------------------------------------------------------
# compose 里映射到宿主机的端口：backend 8080 / web 8000 / mysql 3307 / redis 6379 / chroma 8001
$needPorts = @{
    8080 = 'backend 容器'
    8000 = 'web 容器'
    3307 = 'mysql 容器'
    6379 = 'redis 容器'
    8001 = 'chroma 容器'
}

$busy = @()

# 用 netstat 解析而不是 Get-NetTCPConnection：
# 后者基于 CIM/WMI，在受限环境或某些精简系统上会直接报"拒绝访问"，
# netstat 哪儿都能用，而且能同时拿到 PID。
$listenMap = @{}
$netstatRows = netstat -ano -p tcp 2>$null | Select-String -Pattern 'LISTENING'
foreach ($row in $netstatRows) {
    if ($row.Line -match 'TCP\s+\S+:(\d+)\s+\S+\s+LISTENING\s+(\d+)') {
        $pt = [int]$matches[1]
        if (-not $listenMap.ContainsKey($pt)) { $listenMap[$pt] = [int]$matches[2] }
    }
}

foreach ($p in $needPorts.Keys | Sort-Object) {
    if ($listenMap.ContainsKey($p)) {
        $pid0 = $listenMap[$p]
        $proc = Get-Process -Id $pid0 -ErrorAction SilentlyContinue
        $name = if ($proc) { $proc.ProcessName } else { '(未知进程)' }
        Bad "端口 $p 被占用（$name，PID $pid0）—— $($needPorts[$p]) 将无法启动"
        $busy += [pscustomobject]@{ Port = $p; Pid = $pid0; Name = $name }
    } else {
        Good "端口 $p 空闲"
    }
}

if ($busy.Count -gt 0) {
    Write-Host ''
    Warn '最常见的占用者就是本机直接跑的 Spring Boot 后端（占 8080）和 Vite 前端（占 5173，不影响）。'
    Warn '容器化部署和本机直接跑，二选一，不要同时开。'
    Write-Host ''
    Write-Host '  要结束这些进程吗？输入 y 结束上面列出的进程，直接回车则中止。' -ForegroundColor Yellow
    if ($Force) { $ans = 'y' } else { $ans = Read-Host '  结束并继续? (y/N)' }
    if ($ans -eq 'y') {
        foreach ($b in $busy) {
            try {
                Stop-Process -Id $b.Pid -Force -ErrorAction Stop
                Good "已结束 $($b.Name)（PID $($b.Pid)），端口 $($b.Port) 释放"
            } catch {
                Bad "无法结束 PID $($b.Pid)：$($_.Exception.Message)"
                $fatal = $true
            }
        }
        Start-Sleep -Seconds 2
    } else {
        Bad '已中止。请手动停掉占用端口的程序后重试。'
        exit 1
    }
}

# ------------------------------------------------------------
Head '3. Ollama（容器要通过宿主机访问它）'
# ------------------------------------------------------------
$ollamaUp = $false
try {
    $tags = Invoke-RestMethod -Uri 'http://localhost:11434/api/tags' -TimeoutSec 8
    $ollamaUp = $true
    $models = ($tags.models | ForEach-Object { $_.name }) -join '、'
    Good "宿主机 Ollama 可达，已加载模型：$models"
} catch {
    Bad '连不上 http://localhost:11434，Ollama 没在运行'
    $fatal = $true
}

if ($ollamaUp) {
    # 环境变量可能设在 Machine 级（管理员用 setx /M 设的），也可能设在 User 级，两边都要看
    $hostEnv = [Environment]::GetEnvironmentVariable('OLLAMA_HOST', 'Machine')
    if (-not $hostEnv) { $hostEnv = [Environment]::GetEnvironmentVariable('OLLAMA_HOST', 'User') }
    $procEnv = $env:OLLAMA_HOST
    if ($hostEnv -like '0.0.0.0*' -or $procEnv -like '0.0.0.0*') {
        Good "OLLAMA_HOST 已设置为 $(if ($hostEnv) { $hostEnv } else { $procEnv })"
    } else {
        Warn 'OLLAMA_HOST 没有设置成 0.0.0.0，容器很可能连不上 Ollama'
        Write-Host '      请执行下面两条命令，然后从托盘图标退出 Ollama 再重新打开：'
        Write-Host '        setx OLLAMA_HOST 0.0.0.0' -ForegroundColor White
        Warn '这一项不阻塞启动，但诊断功能会报"连接不上本地 Ollama"。'
    }
}

# ------------------------------------------------------------
Head '4. 必须存在的文件'
# ------------------------------------------------------------
$files = @(
    'docker-compose.yml',
    'opsai-backend\Dockerfile',
    'opsai-backend\pom.xml',
    'opsai-web\Dockerfile',
    'opsai-web\package.json',
    'opsai-web\nginx.conf',
    'deploy\maven-settings.xml',
    'deploy\mysql\init.sql'
)
foreach ($f in $files) {
    if (Test-Path (Join-Path $Root $f)) { Good $f } else { Bad "缺少 $f"; $fatal = $true }
}

if ($fatal) {
    Write-Host "`n体检未通过，已中止。" -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------
Head '5. 启动容器'
# ------------------------------------------------------------
if ($SkipBuild) {
    Write-Host '  docker compose up -d' -ForegroundColor White
    & $dockerExe compose up -d
} else {
    Write-Host '  docker compose up -d --build   （第一次构建要 10~20 分钟，请耐心等）' -ForegroundColor White
    & $dockerExe compose up -d --build
}
if ($LASTEXITCODE -ne 0) {
    Bad 'docker compose 启动失败，用下面这条看详细日志：'
    Write-Host '    docker compose logs -f backend'
    exit 1
}

# ------------------------------------------------------------
Head '6. 等待服务就绪'
# ------------------------------------------------------------
$deadline = (Get-Date).AddMinutes(5)
$healthy = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 5
    try {
        $h = Invoke-RestMethod -Uri 'http://localhost:8080/api/health' -TimeoutSec 5
        if ($h.status -eq 'ok') { $healthy = $true; break }
    } catch { }
    Write-Host '  ...等待后端就绪'
}

Write-Host ''
& $dockerExe compose ps

if ($healthy) {
    Write-Host "`n后端已就绪。" -ForegroundColor Green
} else {
    Warn '后端 5 分钟内没就绪，看日志排查：docker compose logs -f backend'
}

Write-Host "`n系统入口： http://localhost:8000" -ForegroundColor Green
Write-Host '登录账号： admin / admin123' -ForegroundColor Green
Write-Host ''
Warn '容器里是全新的空数据库，知识库也是空的。首次启动后请导入知识：'
Write-Host '    powershell -ExecutionPolicy Bypass -File scripts\seed-kb.ps1' -ForegroundColor White
