# ============================================================
# 数据备份脚本（支持备份到另一台机器）
#
# 把「整台服务器上的所有业务数据」打成一个压缩包：
#   - MySQL 全库（知识库、故障单、诊断记录、用户、操作日志）
#   - 上传的日志和截图（./data 目录）
# 然后可选地复制到备份机器（共享目录 / NAS）。
#
# 用法：
#   # 只备份到本机 backups\
#   powershell -File scripts\backup.ps1
#
#   # 备份并复制到备份机器（推荐）
#   powershell -File scripts\backup.ps1 -RemotePath "\\BACKUP-PC\opsai-backups"
#   powershell -File scripts\backup.ps1 -RemotePath "\\192.168.1.50\backup\opsai" -RemoteUser "backup" -RemotePassword "xxx"
#
# 参数：
#   -RemotePath      备份机器的共享目录（UNC 路径或已映射的网络盘）
#   -RemoteUser      访问共享用的账号（共享已授权可省略）
#   -RemotePassword  对应密码
#   -KeepDays        本机保留天数，默认 14
#   -RemoteKeepDays  备份机保留天数，默认 30
#
# 建议：用任务计划程序每天凌晨 2 点跑一次。
#       主服务器上所有数据只在这一台机器，这台坏了就是全丢 ——
#       所以必须把备份传到另一台机器，只留本机的备份没有意义。
# ============================================================
param(
    [string]$RemotePath = '',
    [string]$RemoteUser = '',
    [string]$RemotePassword = '',
    [int]$KeepDays = 14,
    [int]$RemoteKeepDays = 30
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$root = Split-Path $PSScriptRoot -Parent
$backupDir = Join-Path $root 'backups'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$work = Join-Path $env:TEMP ("opsai-backup-" + $stamp)
$zipPath = Join-Path $backupDir ("opsai-backup-" + $stamp + ".zip")

function Step($t) { Write-Host ''; Write-Host ("== {0} ==" -f $t) -ForegroundColor Cyan }
function Good($t) { Write-Host ("  [OK] {0}" -f $t) -ForegroundColor Green }
function Warn($t) { Write-Host ("  [注意] {0}" -f $t) -ForegroundColor Yellow }
function Bad($t)  { Write-Host ("  [错误] {0}" -f $t) -ForegroundColor Red; exit 1 }

# 执行外部命令。
# 必须包一层：$ErrorActionPreference='Stop' 时，原生命令写到 stderr 的任何一行
# 都会被当成致命错误，而 mysqldump 正常也会往 stderr 打 "Using a password" 警告。
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

New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
New-Item -ItemType Directory -Path $work -Force | Out-Null

Write-Host ''
Write-Host '  OpsAI 数据备份' -ForegroundColor Green
Write-Host '  ' + ('-' * 46)

# ---------- 1. 找 docker ----------
Step '1/4 准备环境'
$docker = $null
$cmd = Get-Command docker -ErrorAction SilentlyContinue
if ($cmd) { $docker = $cmd.Source }
else {
    foreach ($p in @('E:\docker\resources\bin\docker.exe',
                     'C:\Program Files\Docker\Docker\resources\bin\docker.exe')) {
        if (Test-Path $p) { $docker = $p; break }
    }
}
if (-not $docker) { Bad '没找到 docker 命令' }
Good ("docker: " + $docker)

# 密码从 .env 读，和 compose 保持一致
$pwd = 'root123456'
$envFile = Join-Path $root '.env'
if (Test-Path $envFile) {
    $line = Get-Content $envFile -Encoding UTF8 | Where-Object { $_ -match '^\s*MYSQL_ROOT_PASSWORD\s*=' } | Select-Object -First 1
    if ($line) { $pwd = ($line -replace '^\s*MYSQL_ROOT_PASSWORD\s*=\s*', '').Trim().Trim('"').Trim("'") }
}

$running = & $docker ps --filter 'name=opsai-mysql' --format '{{.Names}}' 2>&1
if (-not $running) { Bad 'opsai-mysql 容器没在运行，请先启动服务' }
Good 'MySQL 容器在运行'

# ---------- 2. 导出数据库 ----------
Step '2/4 导出数据库'
$mysqlFile = Join-Path $work 'mysql-opsai.sql'

# 结果先写进容器内的文件，再 docker cp 出来 —— 避免 PowerShell 管道破坏编码
$dumpCmd = "mysqldump -uroot -p'" + $pwd + "' --single-transaction --routines --default-character-set=utf8mb4 opsai > /tmp/opsai.sql && echo DUMP_OK"
$code = Invoke-Native { & $docker exec opsai-mysql sh -c $dumpCmd } 'DUMP_OK|ERROR'
if ($code -ne 0) { Bad 'mysqldump 失败' }

& $docker cp 'opsai-mysql:/tmp/opsai.sql' $mysqlFile 2>&1 | Out-Null
& $docker exec opsai-mysql rm -f /tmp/opsai.sql 2>&1 | Out-Null

if (-not (Test-Path $mysqlFile) -or (Get-Item $mysqlFile).Length -lt 100) { Bad '导出文件异常（内容为空）' }
Good ("数据库导出: " + [Math]::Round((Get-Item $mysqlFile).Length/1KB,0) + " KB")

# ---------- 3. 打包上传文件 + 压缩 ----------
Step '3/4 打包上传文件并压缩'
$dataDir = Join-Path $root 'data'
if (Test-Path $dataDir) {
    Copy-Item $dataDir (Join-Path $work 'data') -Recurse -Force
    $files = Get-ChildItem (Join-Path $work 'data') -Recurse -File -ErrorAction SilentlyContinue
    $sz = ($files | Measure-Object Length -Sum).Sum
    Good ("上传文件: {0} 个，{1:N1} MB" -f $files.Count, ($sz/1MB))
} else {
    Warn '没有 data 目录，跳过上传文件'
}

$info = @()
$info += "备份时间: " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
$info += "主机: " + $env:COMPUTERNAME
$info += "项目路径: " + $root
try { $gh = & git -C $root rev-parse --short HEAD 2>$null; if ($gh) { $info += "Git 提交: " + $gh } } catch { }
[System.IO.File]::WriteAllText((Join-Path $work 'BACKUP-INFO.txt'), ($info -join "`r`n"), (New-Object System.Text.UTF8Encoding($false)))

if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
Compress-Archive -Path (Join-Path $work '*') -DestinationPath $zipPath -CompressionLevel Optimal
Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
Good ("本地备份: " + $zipPath + "（" + [Math]::Round((Get-Item $zipPath).Length/1MB,1) + " MB）")

# ---------- 4. 复制到备份机器 ----------
Step '4/4 复制到备份机器'
if ([string]::IsNullOrWhiteSpace($RemotePath)) {
    Warn '没有指定 -RemotePath，只备份在本机'
    Warn '所有数据只在这台服务器上，这台坏了就全丢，强烈建议用 -RemotePath 指向备份机器'
} else {
    $ok = $false
    if (-not [string]::IsNullOrWhiteSpace($RemoteUser)) {
        $code = Invoke-Native { & net.exe use $RemotePath /user:$RemoteUser $RemotePassword } 'successfully|已完成|error|错误|系统发生'
        if ($code -ne 0) { Warn ("建立共享连接失败（返回 " + $code + "），尝试直接复制") }
    }
    try {
        $remoteFile = Join-Path $RemotePath (Split-Path $zipPath -Leaf)
        Copy-Item $zipPath $remoteFile -Force -ErrorAction Stop
        Good ("已复制到: " + $remoteFile)
        $ok = $true
    } catch {
        Warn ("复制到备份机器失败: " + $_.Exception.Message)
        Warn '本机备份仍在，请检查共享路径 / 账号 / 网络'
    }
    if (-not [string]::IsNullOrWhiteSpace($RemoteUser)) {
        & net.exe use $RemotePath /delete /y 2>&1 | Out-Null
    }

    if ($ok -and $RemoteKeepDays -gt 0) {
        try {
            $cut = (Get-Date).AddDays(-$RemoteKeepDays)
            $oldRemote = Get-ChildItem $RemotePath -Filter 'opsai-backup-*.zip' -ErrorAction Stop |
                         Where-Object { $_.LastWriteTime -lt $cut }
            if ($oldRemote) {
                $oldRemote | ForEach-Object { Remove-Item $_.FullName -Force; Write-Host ("  清理远端过期: " + $_.Name) -ForegroundColor DarkGray }
            }
        } catch {
            Warn ("清理远端旧备份失败: " + $_.Exception.Message)
        }
    }
}

# ---------- 清理本机过期备份 ----------
if ($KeepDays -gt 0) {
    $cut = (Get-Date).AddDays(-$KeepDays)
    $old = Get-ChildItem $backupDir -Filter 'opsai-backup-*.zip' | Where-Object { $_.LastWriteTime -lt $cut }
    if ($old) {
        $old | ForEach-Object { Remove-Item $_.FullName -Force; Write-Host ("  清理本机过期: " + $_.Name) -ForegroundColor DarkGray }
    }
}

$all = Get-ChildItem $backupDir -Filter 'opsai-backup-*.zip' -ErrorAction SilentlyContinue
Write-Host ''
Write-Host ("  本机共 " + $all.Count + " 个备份（保留 " + $KeepDays + " 天）")
Write-Host ''
Write-Host '  恢复步骤：' -ForegroundColor DarkGray
Write-Host '    1) docker compose down' -ForegroundColor DarkGray
Write-Host '    2) 解压 zip 到临时目录' -ForegroundColor DarkGray
Write-Host '    3) docker compose up -d mysql' -ForegroundColor DarkGray
Write-Host '       docker cp mysql-opsai.sql opsai-mysql:/tmp/' -ForegroundColor DarkGray
Write-Host '       docker exec opsai-mysql sh -c "mysql -uroot -p密码 opsai < /tmp/mysql-opsai.sql"' -ForegroundColor DarkGray
Write-Host '    4) 把 data 目录覆盖回项目根目录' -ForegroundColor DarkGray
Write-Host '    5) docker compose up -d' -ForegroundColor DarkGray
