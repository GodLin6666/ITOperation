# ============================================================
# 知识库批量导入脚本
# 读取 opsai\knowledge\ 下的种子文件（kb-seed.json + kb-desktop.json），
# 逐条调用 /api/kb/document，每条自动切块（400 字/块）并调用 bge-m3 算 1024 维向量
#
# 用法：powershell -ExecutionPolicy Bypass -File opsai\scripts\seed-kb.ps1
# 可选参数：-Base http://localhost:8080   -User admin   -Pass admin123
# ============================================================
param(
    [string]$Base = 'http://localhost:8080',
    [string]$User = 'admin',
    [string]$Pass = 'admin123'
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

# 需要导入的种子文件（都放在 knowledge/ 目录下）
$seedFiles = @('kb-seed.json', 'kb-desktop.json')

function Post-Json([string]$uri, $obj, [string]$token) {
    $json = $obj | ConvertTo-Json -Depth 10 -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    $headers = @{}
    if ($token) { $headers['X-Token'] = $token }
    Invoke-RestMethod -Uri "$Base$uri" -Method Post -Body $bytes `
        -ContentType 'application/json; charset=utf-8' -Headers $headers -TimeoutSec 600
}

function Get-Json([string]$uri, [string]$token) {
    $headers = @{}
    if ($token) { $headers['X-Token'] = $token }
    Invoke-RestMethod -Uri "$Base$uri" -Headers $headers -TimeoutSec 600
}

Write-Host "=== 1. 登录 ===" -ForegroundColor Cyan
$login = Post-Json '/api/auth/login' @{ username = $User; password = $Pass }
$token = $login.token
Write-Host "已登录：$($login.realName)"

Write-Host "`n=== 2. 读取种子数据 ===" -ForegroundColor Cyan
$docs = @()
foreach ($f in $seedFiles) {
    $p = Join-Path $PSScriptRoot "..\knowledge\$f"
    if (-not (Test-Path $p)) { Write-Host ("  跳过（文件不存在）：" + $f) -ForegroundColor DarkGray; continue }
    $parsed = Get-Content $p -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($null -eq $parsed) { continue }
    # 注意：不能写成 @(Get-Content ... | ConvertFrom-Json)。
    # 那样 PowerShell 会把整个 JSON 数组当成「一个元素」，结果只读到 1 条。
    $part = [object[]]$parsed
    Write-Host ("  {0,-18} {1,3} 条" -f $f, $part.Count)
    $docs += $part
}
Write-Host ("种子合计 {0} 条知识" -f $docs.Count)

Write-Host "`n=== 3. 检查已有知识（按标题去重）===" -ForegroundColor Cyan
$existing = Get-Json '/api/kb/documents' $token
$existingTitles = @{}
foreach ($d in $existing) { $existingTitles[$d.title] = $true }
Write-Host "库里已存在 $($existing.Count) 条"

Write-Host "`n=== 4. 逐条导入（每条要算向量，请耐心等待）===" -ForegroundColor Cyan
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$ok = 0
$skip = 0
$fail = 0
$n = 0

foreach ($doc in $docs) {
    $n++
    if ($existingTitles.ContainsKey($doc.title)) {
        Write-Host ("  [{0}/{1}] 跳过（已存在）：{2}" -f $n, $docs.Count, $doc.title) -ForegroundColor DarkGray
        $skip++
        continue
    }
    try {
        $r = Post-Json '/api/kb/document' $doc $token
        Write-Host ("  [{0}/{1}] OK  id={2}  分块={3}  {4}" -f $n, $docs.Count, $r.id, $r.chunkCount, $r.title) -ForegroundColor Green
        $ok++
    } catch {
        Write-Host ("  [{0}/{1}] 失败：{2}  ->  {3}" -f $n, $docs.Count, $doc.title, $_.Exception.Message) -ForegroundColor Red
        $fail++
    }
}
$sw.Stop()

Write-Host "`n=== 5. 汇总 ===" -ForegroundColor Cyan
Write-Host ("新增 {0} 条，跳过 {1} 条，失败 {2} 条，耗时 {3:N1} 秒" -f $ok, $skip, $fail, $sw.Elapsed.TotalSeconds)

$pub = Get-Json '/api/kb/documents/published' $token
$chunks = 0
$byDevice = @{}
foreach ($d in $pub) {
    $chunks += $d.chunkCount
    $k = $d.deviceType
    if ($byDevice.ContainsKey($k)) { $byDevice[$k]++ } else { $byDevice[$k] = 1 }
}
Write-Host ("已发布知识 {0} 条，向量分块合计 {1} 块" -f $pub.Count, $chunks)
Write-Host "按设备类型分布："
$byDevice.GetEnumerator() | Sort-Object Name | ForEach-Object { Write-Host ("  {0,-12} {1} 条" -f $_.Key, $_.Value) }
