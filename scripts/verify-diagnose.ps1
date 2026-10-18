# ============================================================
# 验收脚本：健康检查 -> 登录 -> 灌 3 条知识 -> 跑一次诊断
# 用法：在任意目录执行  pwsh -File opsai\scripts\verify-diagnose.ps1
# ============================================================
$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

function Post-Json([string]$uri, $obj, [string]$token) {
    $json  = $obj | ConvertTo-Json -Depth 10 -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    $headers = @{}
    if ($token) { $headers['X-Token'] = $token }
    Invoke-RestMethod -Uri "$base$uri" -Method Post -Body $bytes `
        -ContentType 'application/json; charset=utf-8' -Headers $headers -TimeoutSec 600
}

function Get-Json([string]$uri, [string]$token) {
    $headers = @{}
    if ($token) { $headers['X-Token'] = $token }
    Invoke-RestMethod -Uri "$base$uri" -Headers $headers -TimeoutSec 600
}

Write-Host '=== 1. 健康检查 ===' -ForegroundColor Cyan
$health = Get-Json '/api/health'
$health | ConvertTo-Json -Depth 6
if ($health.status -ne 'ok') { throw "健康检查失败" }

Write-Host "`n=== 2. 登录 ===" -ForegroundColor Cyan
$login = Post-Json '/api/auth/login' @{ username = 'admin'; password = 'admin123' }
$token = $login.token
Write-Host "登录成功：$($login.realName)（角色 $($login.role)）"

Write-Host "`n=== 3. 灌入 3 条知识（每条都会自动分块 + 算向量）===" -ForegroundColor Cyan

$docs = @()
$docs += @{
    title = 'MySQL 连接数打满排查手册'; deviceType = 'db'; faultCategory = 'db'; docType = 'manual'
    content = "现象：应用日志出现 Too many connections 或 HikariPool 连接超时。影响：所有依赖该库的接口报错，业务不可用。可能原因：1）应用连接池配置过大；2）连接用完没有归还（代码未关闭连接）；3）慢查询导致连接长时间占用；4）max_connections 设置偏小；5）存在空闲连接未回收。排查步骤：1）执行 show status like 'Threads_connected' 查看当前连接数；2）执行 show variables like 'max_connections' 查看上限；3）执行 show processlist 找出大量 Sleeping 状态的连接及其来源 IP；4）检查应用连接池配置，HikariCP 的 maximumPoolSize 是否设置过大，是否存在连接用完没归还；5）如果是慢查询导致连接堆积，用 show engine innodb status 或慢查询日志定位；6）临时处置：kill 掉长时间空闲的连接，或临时调大 max_connections；7）根本处置：修复未关闭的连接、优化慢 SQL、合理设置连接池和 wait_timeout。预防措施：给 Threads_connected 设置监控告警，连接池 maximumPoolSize 按数据库承载能力评估，慢查询定期治理。"
}
$docs += @{
    title = 'Linux 磁盘空间占满排查手册'; deviceType = 'server'; faultCategory = 'disk'; docType = 'manual'
    content = "现象：服务写日志报 No space left on device，或 df 显示分区使用率 100%。影响：服务无法写入日志与数据，可能直接崩溃或拒绝服务。可能原因：1）日志文件没有轮转；2）临时文件堆积；3）被删除但仍被进程占用的文件；4）core dump 文件；5）数据库数据文件暴涨。排查步骤：1）执行 df -h 找到使用率 100% 的分区；2）执行 du -sh /* 逐层定位占空间最大的目录；3）常见元凶是日志文件、临时文件、被删除但仍被进程占用的文件，用 lsof | grep deleted 检查；4）如果是日志文件过大，先 truncate 或 logrotate 清理，不要直接 rm 正在写的文件；5）用 find / -size +500M 找大文件；6）处置后确认 df -h 恢复，并检查服务日志是否恢复写入；7）长期方案：配置日志轮转和磁盘使用率告警。预防措施：日志必须配置 logrotate，磁盘使用率超过 80% 就告警，核心分区独立挂载。"
}
$docs += @{
    title = 'CPU 使用率飙高排查手册'; deviceType = 'server'; faultCategory = 'cpu'; docType = 'manual'
    content = "现象：监控告警 CPU 使用率持续高于 90%，业务响应变慢。影响：接口响应时间上升，严重时请求大量超时。可能原因：1）业务代码死循环或热点方法；2）频繁 Full GC；3）锁竞争导致线程自旋；4）定时任务或批处理在高峰期执行；5）外部攻击或爬虫流量。排查步骤：1）用 top 或 htop 找到占用最高的进程 PID；2）执行 top -Hp 进程号 找到占用高的线程 TID；3）把 TID 转成十六进制后用 jstack 进程号 查看该线程的堆栈，判断是业务代码死循环、GC 线程还是锁竞争；4）检查是否有定时任务或批处理在高峰期执行；5）检查是否 CPU 上下文切换过多，用 vmstat 1 看 cs 和 wa 列；6）临时处置：限流、降级非核心任务或重启异常进程；7）根本处置：优化热点代码、调整线程池、错峰调度任务。预防措施：CPU 使用率超过 80% 持续 5 分钟告警，定期做压测和 GC 日志分析。"
}

foreach ($d in $docs) {
    $r = Post-Json '/api/kb/document' $d $token
    Write-Host ("  已录入：id={0} 标题={1} 分块数={2}" -f $r.id, $r.title, $r.chunkCount)
}

Write-Host "`n=== 4. 提交一段真实日志，跑一次诊断（首次调用要加载模型，耐心等）===" -ForegroundColor Cyan

$logText = @"
2026-10-09 10:12:31 ERROR [order-service] com.zaxxer.hikari.HikariPool - Connection is not available, request timed out after 30000ms
2026-10-09 10:12:32 ERROR [order-service] java.sql.SQLNonTransientConnectionException: Data source rejected establishment of connection, message from server: "Too many connections"
2026-10-09 10:12:33 WARN  [order-service] HikariPool-1 - Pool stats (total=50, active=50, idle=0, waiting=12)
2026-10-09 10:12:35 ERROR [order-service] Timeout waiting for connection from pool
"@

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$diag = Post-Json '/api/diagnose' @{
    title = '订单服务连接数据库超时'
    deviceType = 'db'
    faultCategory = 'db'
    phenomenon = '订单服务从 10:12 开始大量报错，接口 500，重启无效'
    logText = $logText
} $token
$sw.Stop()

Write-Host "`n--- 预处理结果 ---"
Write-Host ("关键词: " + ($diag.keywords -join '、'))
Write-Host ("原始日志行数 {0}，送入模型 {1} 行" -f $diag.rawLineCount, $diag.usedLineCount)
$diag.templates | Select-Object -First 4 | ForEach-Object { Write-Host ("  模板: {0}（{1} 次）" -f $_.pattern, $_.count) }

Write-Host "`n--- 检索命中 ---"
$i = 1
foreach ($h in $diag.hits) {
    Write-Host ("  [资料{0}] {1}  相似度 {2:N4}" -f $i, $h.title, $h.score)
    $i++
}

Write-Host "`n--- 模型输出的结构化建议 ---"
Write-Host ("结论：" + $diag.answer.summary)
$n = 1
foreach ($c in $diag.answer.causes) {
    Write-Host ("`n原因{0}：{1}（可信度 {2}）" -f $n, $c.cause, $c.confidence)
    Write-Host ("  依据：{0}" -f $c.evidence)
    Write-Host "  排查步骤："
    $s = 1
    foreach ($step in $c.checkSteps) { Write-Host ("    {0}) {1}" -f $s, $step); $s++ }
    Write-Host ("  处置建议：{0}" -f $c.fix)
    $n++
}
if ($diag.answer.needMoreInfo) {
    Write-Host "`n还需要补充的信息："
    $diag.answer.needMoreInfo | ForEach-Object { Write-Host ("  - " + $_) }
}

Write-Host "`n--- 性能指标 ---" -ForegroundColor Yellow
Write-Host ("首字耗时 {0} ms（项目需求要求 <5000）｜总耗时 {1} ms｜接口往返 {2} ms" -f `
    $diag.firstTokenMs, $diag.costMs, $sw.ElapsedMilliseconds)
Write-Host ("ticketId = {0}" -f $diag.ticketId)

Write-Host "`n=== 验收判定 ===" -ForegroundColor Green
$pass = $true
if ($diag.hits.Count -eq 0) { Write-Host "  [X] 检索命中为空" -ForegroundColor Red; $pass = $false }
else {
    Write-Host ("  [OK] 检索命中 {0} 条，Top1 = {1}（{2:N4}）" -f $diag.hits.Count, $diag.hits[0].title, $diag.hits[0].score)
}
if (-not $diag.answer.causes -or $diag.answer.causes.Count -eq 0) { Write-Host "  [X] 没有解析出 causes" -ForegroundColor Red; $pass = $false }
else { Write-Host ("  [OK] 解析出 {0} 条可能原因" -f $diag.answer.causes.Count) }
if ($diag.firstTokenMs -le 5000) { Write-Host ("  [OK] 首字耗时 {0} ms <= 5000 ms" -f $diag.firstTokenMs) }
else { Write-Host ("  [!] 首字耗时 {0} ms 超过 5000 ms" -f $diag.firstTokenMs) -ForegroundColor Yellow }
if ($pass) { Write-Host "`n验收通过。" -ForegroundColor Green } else { Write-Host "`n验收未通过。" -ForegroundColor Red }
