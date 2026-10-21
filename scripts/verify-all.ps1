# ============================================================
# 全量验收脚本：对照验收清单「验收清单」逐项实测
#
# 用法：powershell -ExecutionPolicy Bypass -File opsai\scripts\verify-all.ps1
# 前置：后端 8080 已启动，Ollama 已启动，数据库已建表并导入知识
# ============================================================
$ErrorActionPreference = 'Continue'
$Base = 'http://localhost:8080'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$results = New-Object System.Collections.ArrayList
function Check([string]$name, [bool]$ok, [string]$detail) {
    $null = $results.Add([pscustomobject]@{ 项目 = $name; 结果 = $(if ($ok) { '通过' } else { '未通过' }); 说明 = $detail })
    $color = if ($ok) { 'Green' } else { 'Red' }
    Write-Host ("  [{0}] {1}  {2}" -f $(if ($ok) { 'OK' } else { 'X ' }), $name, $detail) -ForegroundColor $color
}

function Post-Json([string]$uri, $obj, [string]$token) {
    $json = $obj | ConvertTo-Json -Depth 10 -Compress
    $headers = @{}
    if ($token) { $headers['X-Token'] = $token }
    Invoke-RestMethod -Uri "$Base$uri" -Method Post -Body ([System.Text.Encoding]::UTF8.GetBytes($json)) `
        -ContentType 'application/json; charset=utf-8' -Headers $headers -TimeoutSec 600
}
function Get-Json([string]$uri, [string]$token) {
    $headers = @{}
    if ($token) { $headers['X-Token'] = $token }
    Invoke-RestMethod -Uri "$Base$uri" -Headers $headers -TimeoutSec 600
}

Write-Host "`n================ 基础连通 ================" -ForegroundColor Cyan
try {
    $h = Get-Json '/api/health'
    Check '健康检查 /api/health' ($h.status -eq 'ok') "status=$($h.status)"
    Check 'Ollama 与两个模型就绪' ($h.llm.chatModelReady -and $h.llm.embedModelReady) ($h.llm.models -join ', ')
} catch { Check '健康检查 /api/health' $false $_.Exception.Message }

try {
    $eb = @{ model = 'bge-m3'; input = '磁盘使用率 100%' } | ConvertTo-Json
    $er = Invoke-RestMethod -Uri 'http://localhost:11434/api/embed' -Method Post -Body ([System.Text.Encoding]::UTF8.GetBytes($eb)) -ContentType 'application/json' -TimeoutSec 120
    Check 'bge-m3 返回 1024 维向量' ($er.embeddings[0].Count -eq 1024) "维度=$($er.embeddings[0].Count)"
} catch { Check 'bge-m3 返回 1024 维向量' $false $_.Exception.Message }
# 预热对话模型。
# 容器刚重启时模型还没进显存，第一次推理要重新加载（实测首字 9 秒以上），
# 那样后面的「首字耗时」测的是冷启动，没有参考价值。
# 应用本身给每个请求带了 keep_alive=30m，正常应该是热态，所以这里先打一次极小请求。
try {
    $warmBody = @{
        model   = 'qwen2.5:7b-instruct-q4_K_M'
        prompt  = 'ping'
        stream  = $false
        options = @{ num_predict = 1 }
    } | ConvertTo-Json -Depth 5
    $null = Invoke-RestMethod -Uri 'http://localhost:11434/api/generate' -Method Post `
        -Body ([System.Text.Encoding]::UTF8.GetBytes($warmBody)) -ContentType 'application/json' -TimeoutSec 300
    Check '对话模型预热（保证首字耗时测的是热态）' $true '已加载进显存'
} catch { Check '对话模型预热' $false $_.Exception.Message }

Write-Host "`n================ 登录与角色权限 ================" -ForegroundColor Cyan
$tokens = @{}
foreach ($acc in @(@('admin', 'admin123'), @('engineer', 'engineer123'), @('viewer', 'viewer123'))) {
    try {
        $r = Post-Json '/api/auth/login' @{ username = $acc[0]; password = $acc[1] }
        $tokens[$acc[0]] = $r.token
        Check "登录 $($acc[0])（$($r.role)）" ($null -ne $r.token) $r.realName
    } catch { Check "登录 $($acc[0])" $false $_.Exception.Message }
}

# viewer 不能执行写操作
try {
    $null = Post-Json '/api/kb/document' @{ title = 'viewer不该成功'; deviceType = 'server'; faultCategory = 'cpu'; content = 'x' } $tokens['viewer']
    Check 'viewer 角色被拒绝写操作' $false '居然成功了'
} catch { Check 'viewer 角色被拒绝写操作' ($_.Exception.Response.StatusCode.value__ -eq 403) "HTTP $($_.Exception.Response.StatusCode.value__)" }

# engineer 不能审核知识
try {
    $null = Post-Json '/api/kb/document/1/audit?status=1' @{} $tokens['engineer']
    Check 'engineer 不能审核知识（需管理员）' $false '居然成功了'
} catch { Check 'engineer 不能审核知识（需管理员）' ($_.Exception.Response.StatusCode.value__ -eq 403) "HTTP $($_.Exception.Response.StatusCode.value__)" }

# 未登录不能访问
try {
    $null = Get-Json '/api/kb/documents' ''
    Check '未登录被拦截' $false '居然成功了'
} catch { Check '未登录被拦截' ($_.Exception.Response.StatusCode.value__ -eq 401) "HTTP $($_.Exception.Response.StatusCode.value__)" }

$token = $tokens['admin']

Write-Host "`n================ 知识库 ================" -ForegroundColor Cyan
try {
    $all = Get-Json '/api/kb/documents' $token
    $pub = Get-Json '/api/kb/documents/published' $token
    $totalChunks = ($pub | Measure-Object -Property chunkCount -Sum).Sum
    Check '知识库列表可读' ($all.Count -gt 0) "共 $($all.Count) 条"
    Check '已发布知识数量' ($pub.Count -ge 25) "已发布 $($pub.Count) 条"
    Check '知识已切块并向量化' ($totalChunks -gt 0) "向量分块合计 $totalChunks 块"
    $byDevice = $pub | Group-Object deviceType | ForEach-Object { "$($_.Name)=$($_.Count)" }
    Write-Host ("      设备类型分布： " + ($byDevice -join '  ')) -ForegroundColor DarkGray
} catch { Check '知识库列表可读' $false $_.Exception.Message }

# 新增 + 审核 + 分类查看 + 删除
try {
    $nd = Post-Json '/api/kb/document' @{
        title = '验收测试条目-可删除'; deviceType = 'network'; faultCategory = 'network'
        docType = 'manual'; content = '现象：这是一条用于验收测试的知识条目。影响：无。可能原因：测试。排查步骤：1）确认这是测试数据；2）验收完成后应删除。处置方案：验收脚本会自动删除该条。预防措施：不要在生产库中保留测试数据。'
    } $token
    Check '新增知识并自动向量化' ($nd.id -gt 0 -and $nd.chunkCount -ge 1) "id=$($nd.id) 分块=$($nd.chunkCount)"

    $au = Post-Json "/api/kb/document/$($nd.id)/audit?status=2" @{} $token
    Check '知识审核（驳回）' ($au.status -eq 2) "status=$($au.status)"

    $net = (Get-Json '/api/kb/documents' $token) | Where-Object { $_.deviceType -eq 'network' }
    Check '按设备类型分类查看' ($net.Count -gt 0) "network 类 $($net.Count) 条"

    Invoke-RestMethod -Uri "$Base/api/kb/document/$($nd.id)" -Method Delete -Headers @{'X-Token' = $token} -TimeoutSec 60 | Out-Null
    $after = Get-Json '/api/kb/documents' $token
    Check '删除知识' (($after | Where-Object { $_.id -eq $nd.id }).Count -eq 0) '已删除测试条目'
} catch { Check '知识库增删改查' $false $_.Exception.Message }

Write-Host "`n================ 核心诊断 /api/diagnose ================" -ForegroundColor Cyan
$logText = @"
2026-10-09 10:12:31 ERROR [order-service] com.zaxxer.hikari.HikariPool - Connection is not available, request timed out after 30000ms
2026-10-09 10:12:32 ERROR [order-service] java.sql.SQLNonTransientConnectionException: Data source rejected establishment of connection, message from server: "Too many connections"
2026-10-09 10:12:33 WARN  [order-service] HikariPool-1 - Pool stats (total=50, active=50, idle=0, waiting=12)
2026-10-09 10:12:35 ERROR [order-service] Timeout waiting for connection from pool
"@
$diag = $null
$sw = [System.Diagnostics.Stopwatch]::StartNew()
try {
    $diag = Post-Json '/api/diagnose' @{
        title = '验收-订单服务连接数据库超时'; deviceType = 'db'; faultCategory = 'db'
        phenomenon = '订单服务从 10:12 开始大量报错，接口 500，重启无效'; logText = $logText
    } $token
    $sw.Stop()
} catch { Check '一次诊断调用' $false $_.Exception.Message }

if ($diag) {
    Check '诊断返回结构化结果' ($null -ne $diag.answer.summary) "summary=$($diag.answer.summary.Substring(0, [Math]::Min(40, $diag.answer.summary.Length)))…"
    Check '输出包含可能原因' ($diag.answer.causes.Count -ge 1) "共 $($diag.answer.causes.Count) 条原因"
    $hasSteps = $false; $hasFix = $false; $hasEvidence = $false
    foreach ($c in $diag.answer.causes) {
        if ($c.checkSteps -and $c.checkSteps.Count -gt 0) { $hasSteps = $true }
        if ($c.fix) { $hasFix = $true }
        if ($c.evidence -match '\[资料\d+\]') { $hasEvidence = $true }
    }
    Check '每条原因带排查步骤' $hasSteps ''
    Check '每条原因带处置建议' $hasFix ''
    Check '依据里出现 [资料N] 引用' $hasEvidence ''

    Check '检索命中并带相似度' ($diag.hits.Count -gt 0) "命中 $($diag.hits.Count) 条，Top1 相似度 $([Math]::Round($diag.hits[0].score, 4))"
    $sorted = $true
    for ($i = 1; $i -lt $diag.hits.Count; $i++) { if ($diag.hits[$i].score -gt $diag.hits[$i - 1].score) { $sorted = $false } }
    Check '相似案例按相似度降序排列' $sorted ("Top1 = " + $diag.hits[0].title)
    Check 'Top1 命中相关知识' ($diag.hits[0].title -like '*MySQL*' -or $diag.hits[0].title -like '*连接*') $diag.hits[0].title

    Check '性能：首字返回 ≤ 5000ms' ($diag.firstTokenMs -le 5000) "首字 $($diag.firstTokenMs) ms"
    Check '性能：总耗时已记录' ($diag.costMs -gt 0) "总耗时 $($diag.costMs) ms，接口往返 $($sw.ElapsedMilliseconds) ms"
    Check '日志预处理产出模板' ($diag.templates.Count -gt 0) "模板 $($diag.templates.Count) 个，关键词 $($diag.keywords.Count) 个"
    Check '日志长度保护（截断到 80 行内）' ($diag.usedLineCount -le 80) "原始 $($diag.rawLineCount) 行 -> 送入模型 $($diag.usedLineCount) 行"

    Write-Host "`n================ 日志模板归并 ================" -ForegroundColor Cyan
    # 三条只有时间戳不同的同类日志，应该被归并成同一个模板并统计出现 3 次
    $dupLog = @"
2026-10-09 10:12:31 ERROR Connection refused, port=3306, attempts=3
2026-10-09 10:12:33 ERROR Connection refused, port=3306, attempts=3
2026-10-09 10:12:35 ERROR Connection refused, port=3306, attempts=3
"@
    try {
        $dup = Post-Json '/api/diagnose' @{
            title = '验收-日志模板归并'; deviceType = 'db'; faultCategory = 'db'
            phenomenon = '数据库连接被拒绝'; logText = $dupLog
        } $token
        $top = $dup.templates | Where-Object { $_.pattern -like '*Connection refused*' } | Select-Object -First 1
        Check '同一类日志归并为同一模板并计数' ($null -ne $top -and $top.count -eq 3) "模板出现 $($top.count) 次"
        Check '模板中可变数字被替换为占位符' ($top.pattern -like '*<NUM>*' -and $top.pattern -like '*<TS>*') $top.pattern
    } catch { Check '日志模板归并' $false $_.Exception.Message }

    Write-Host "`n================ 追问 ================" -ForegroundColor Cyan
    try {
        $fu = Post-Json '/api/diagnose/followup' @{ ticketId = $diag.ticketId; question = '第 1 步的 show processlist 具体该看哪几列？' } $token
        Check '追问接口返回回答' ($fu.answer.Length -gt 10) "首字 $($fu.firstTokenMs) ms，回答 $($fu.answer.Length) 字"
    } catch { Check '追问接口返回回答' $false $_.Exception.Message }

    Write-Host "`n================ 采纳 / 导出 / 案例 ================" -ForegroundColor Cyan
    try {
        $ad = Post-Json "/api/tickets/$($diag.ticketId)/adopt?adopted=true" @{} $token
        Check '标记建议已采纳' ($ad.adopted -eq $true) ''
    } catch { Check '标记建议已采纳' $false $_.Exception.Message }

    try {
        $tmp = Join-Path $env:TEMP "opsai-ticket-$($diag.ticketId).docx"
        Invoke-WebRequest -Uri "$Base/api/tickets/$($diag.ticketId)/export" -Headers @{'X-Token' = $token} -OutFile $tmp -TimeoutSec 120
        $bytes = [System.IO.File]::ReadAllBytes($tmp)
        $isZip = ($bytes[0] -eq 0x50 -and $bytes[1] -eq 0x4B)
        Check '导出处理单为 Word (.docx)' ($isZip -and $bytes.Length -gt 5000) "$([Math]::Round($bytes.Length/1024,1)) KB，文件头 $(if($isZip){'PK/合法docx'}else{'异常'})"
        Write-Host "      导出文件：$tmp" -ForegroundColor DarkGray
    } catch { Check '导出处理单为 Word (.docx)' $false $_.Exception.Message }

    try {
        $list = Get-Json '/api/tickets' $token
        Check '历史案例列表' ($list.Count -gt 0) "共 $($list.Count) 条"
        $detail = Get-Json "/api/tickets/$($diag.ticketId)" $token
        Check '案例详情含诊断记录与命中资料' ($detail.records.Count -gt 0 -and $detail.records[0].hits.Count -gt 0) "记录 $($detail.records.Count) 条"
    } catch { Check '案例列表与详情' $false $_.Exception.Message }
}

Write-Host "`n================ 统计 ================" -ForegroundColor Cyan
try {
    $st = Get-Json '/api/stats/overview' $token
    Check '统计：故障类别分布' ($null -ne $st.categoryDist) (($st.categoryDist.PSObject.Properties | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ' ')
    Check '统计：平均处置时长/耗时' ($st.avgCostMs -gt 0) "平均总耗时 $($st.avgCostMs) ms"
    Check '统计：建议采纳率' ($st.adoptRate -ge 0) "采纳率 $($st.adoptRate)%"
    Check '统计：首字耗时指标' ($st.avgFirstTokenMs -gt 0) "平均首字 $($st.avgFirstTokenMs) ms，5 秒内达标率 $($st.firstTokenUnder5sRate)%"
    Check '统计：知识库与向量块计数' ($st.documentCount -gt 0 -and $st.chunkCount -gt 0) "知识 $($st.documentCount) 条 / 向量块 $($st.chunkCount) 块"
} catch { Check '统计接口' $false $_.Exception.Message }

Write-Host "`n================ 前端 ================" -ForegroundColor Cyan
# 两种部署方式都支持：
#   本机直跑    -> Vite dev server  http://localhost:5173（可以直接检查各个 .vue 模块能否转译）
#   Docker 容器 -> Nginx 托管        http://localhost:8000（生产构建，检查首页和静态资源）
$webUrl = $null
$webMode = $null
foreach ($cand in @(@('http://localhost:8000', 'Docker 容器 (Nginx)'), @('http://localhost:5173', '本机 dev server (Vite)'))) {
    try {
        $r0 = Invoke-WebRequest -Uri $cand[0] -TimeoutSec 8 -UseBasicParsing
        if ($r0.StatusCode -eq 200) { $webUrl = $cand[0]; $webMode = $cand[1]; break }
    } catch { }
}

if (-not $webUrl) {
    Check '前端页面可访问' $false '8000 和 5173 都没起来'
} else {
    Check "前端页面可访问（$webMode）" $true $webUrl
    try {
        $r = Invoke-WebRequest -Uri "$webUrl/" -TimeoutSec 15 -UseBasicParsing
        Check '前端首页返回 200' ($r.StatusCode -eq 200) "$($r.Content.Length) 字节"
        $p = Invoke-RestMethod -Uri "$webUrl/api/health" -TimeoutSec 20
        Check '前端代理 /api 到后端' ($p.status -eq 'ok') 'status=ok'
    } catch { Check '前端首页/代理' $false $_.Exception.Message }

    if ($webMode -like '*Vite*') {
        $okMods = 0
        foreach ($m in @('/src/App.vue', '/src/views/Login.vue', '/src/views/Diagnose.vue', '/src/views/Knowledge.vue', '/src/views/Cases.vue', '/src/views/Stats.vue')) {
            try {
                $rr = Invoke-WebRequest -Uri "$webUrl$m" -TimeoutSec 15 -UseBasicParsing
                if ($rr.StatusCode -eq 200 -and $rr.Content -notmatch 'Transform failed|Parse failure') { $okMods++ }
            } catch { }
        }
        Check '6 个页面组件均可编译' ($okMods -eq 6) "$okMods/6 编译通过"
    } else {
        # 生产构建：确认构建产物（带 hash 的 assets）真的被托管出来了
        $html = (Invoke-WebRequest -Uri "$webUrl/" -TimeoutSec 15 -UseBasicParsing).Content
        $assets = [regex]::Matches($html, 'src="(/assets/[^"]+\.js)"') | ForEach-Object { $_.Groups[1].Value }
        $cssOk = $html -match '/assets/[^"]+\.css'
        Check '生产构建的 JS 产物已注入页面' ($assets.Count -ge 1) ("找到 " + $assets.Count + " 个入口 js")
        Check '生产构建的 CSS 产物已注入页面' $cssOk ''
        if ($assets.Count -ge 1) {
            $a = Invoke-WebRequest -Uri "$webUrl$($assets[0])" -TimeoutSec 20 -UseBasicParsing
            Check '静态资源可正常下载' ($a.StatusCode -eq 200) "$($assets[0]) -> $([Math]::Round($a.RawContentLength/1KB,0)) KB"
        }
        # SPA 子路由必须能直接刷新（nginx try_files 有没有配对）
        $sub = Invoke-WebRequest -Uri "$webUrl/cases" -TimeoutSec 15 -UseBasicParsing
        Check 'SPA 子路由直接访问可用（nginx try_files）' ($sub.StatusCode -eq 200 -and $sub.Content -match 'id="app"') 'GET /cases -> 200'
    }
}

Write-Host "`n================ 自愈 ================" -ForegroundColor Cyan
try {
    $m = Get-Json '/api/selfheal/metrics' $tokens['admin']
    Check '自愈指标可读 /api/selfheal/metrics' ($null -ne $m.disk -and $null -ne $m.memory -and $null -ne $m.kb) `
        "磁盘 $($m.disk.usedPct)% / 内存 $($m.memory.usedPct)% / 知识 $($m.kb.documents) 篇"
} catch { Check '自愈指标可读' $false $_.Exception.Message }

try {
    $acts = Get-Json '/api/selfheal/actions' $tokens['admin']
    Check '白名单动作已注册（执行边界）' ($acts.Count -ge 3) (($acts | ForEach-Object { "$($_.key)($($_.risk))" }) -join ', ')
} catch { Check '白名单动作已注册' $false $_.Exception.Message }

try {
    $scan = Post-Json '/api/selfheal/scan' @{} $tokens['admin']
    Check '手动巡检可执行（只采集不改动）' ($null -ne $scan.created) "发现 $($scan.created) 个新问题"
} catch { Check '手动巡检可执行' $false $_.Exception.Message }

try {
    $evs = Get-Json '/api/selfheal/events' $tokens['admin']
    Check '自愈事件列表可读' ($null -ne $evs) "$($evs.Count) 条事件"
} catch { Check '自愈事件列表可读' $false $_.Exception.Message }

# 权限：viewer 完全看不到自愈
try {
    $null = Get-Json '/api/selfheal/metrics' $tokens['viewer']
    Check 'viewer 被拒绝自愈接口' $false '居然成功了'
} catch { Check 'viewer 被拒绝自愈接口' ($_.Exception.Response.StatusCode.value__ -eq 403) "HTTP $($_.Exception.Response.StatusCode.value__)" }

# 权限：engineer 能看指标，但不能执行处置动作
try {
    $null = Get-Json '/api/selfheal/metrics' $tokens['engineer']
    Check 'engineer 可查看自愈指标' $true '允许'
} catch { Check 'engineer 可查看自愈指标' $false "HTTP $($_.Exception.Response.StatusCode.value__)" }

try {
    $null = Post-Json '/api/selfheal/events/1/execute' @{} $tokens['engineer']
    Check 'engineer 不能执行处置动作' $false '居然成功了'
} catch { Check 'engineer 不能执行处置动作（需管理员）' ($_.Exception.Response.StatusCode.value__ -eq 403) "HTTP $($_.Exception.Response.StatusCode.value__)" }

# 安全兜底：不存在的动作一律拒绝（不产生副作用）
try {
    $null = Post-Json '/api/selfheal/events/999999/execute' @{} $tokens['admin']
    Check '不存在的自愈事件拒绝执行' $false '居然成功了'
} catch { Check '不存在的自愈事件拒绝执行' ($true) "HTTP $($_.Exception.Response.StatusCode.value__)" }

Write-Host "`n================ 操作日志 ================" -ForegroundColor Cyan
try {
    $logs = Get-Json '/api/logs?limit=100' $tokens['admin']
    Check '操作日志可查 /api/logs' ($logs.total -ge 1) "共 $($logs.total) 条"
    $acts = ($logs.byAction.PSObject.Properties | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ' '
    Check '日志含动作分布' (-not [string]::IsNullOrWhiteSpace($acts)) $acts
    $loginLogged = $logs.items | Where-Object { $_.action -like '*登录*' } | Select-Object -First 1
    Check '登录动作已落日志' ($null -ne $loginLogged) $(if ($loginLogged) { "$($loginLogged.username) $($loginLogged.action)" } else { '未找到' })
} catch { Check '操作日志可查' $false $_.Exception.Message }

try {
    $null = Get-Json '/api/logs' $tokens['viewer']
    Check 'viewer 被拒绝操作日志' $false '居然成功了'
} catch { Check 'viewer 被拒绝操作日志' ($_.Exception.Response.StatusCode.value__ -eq 403) "HTTP $($_.Exception.Response.StatusCode.value__)" }

Write-Host "`n================ 只读用户极简问答 ================" -ForegroundColor Cyan
try {
    $ans = Post-Json '/api/assist/ask' @{ question = '电脑很卡，打开什么都慢，怎么排查'; title = '验收-只读用户提问' } $tokens['viewer']
    Check 'viewer 可提问 /api/assist/ask' ($null -ne $ans.conclusion) $ans.conclusion
    Check '返回最多 3 条原因' ($ans.causes.Count -le 3) "$($ans.causes.Count) 条"
    $fields = ($ans.PSObject.Properties.Name) -join ', '
    Check '回答不含内部字段（模板/命中/向量）' (-not ($ans.PSObject.Properties.Name -contains 'hits') -and -not ($ans.PSObject.Properties.Name -contains 'templates')) $fields
    Check '回答带引用来源' ($ans.sources.Count -ge 1) (($ans.sources | ForEach-Object { $_.title }) -join ' / ')
} catch { Check 'viewer 可提问' $false $_.Exception.Message }

# viewer 不能访问任何管理数据
foreach ($ep in @('/api/kb/documents', '/api/tickets', '/api/stats/overview')) {
    try {
        $null = Get-Json $ep $tokens['viewer']
        Check "viewer 被拒绝 $ep" $false '居然成功了'
    } catch { Check "viewer 被拒绝 $ep" ($_.Exception.Response.StatusCode.value__ -eq 403) "HTTP $($_.Exception.Response.StatusCode.value__)" }
}

# /api/diagnose 是 POST 接口，必须用 POST 测，否则测到的是 405 而不是权限拒绝
try {
    $null = Post-Json '/api/diagnose' @{ title = 'viewer越权测试'; deviceType = 'app'; faultCategory = 'app'; phenomenon = 'x'; logText = 'x' } $tokens['viewer']
    Check 'viewer 被拒绝 /api/diagnose' $false '居然成功了'
} catch { Check 'viewer 被拒绝 /api/diagnose' ($_.Exception.Response.StatusCode.value__ -eq 403) "HTTP $($_.Exception.Response.StatusCode.value__)" }
Write-Host "`n================ 数据库 ================" -ForegroundColor Cyan
# 两种部署方式查库的方式不一样：
#   Docker 部署 -> 用 docker exec 进容器查。
#                  （宿主机 mysql 客户端跨主机连 root 会被 MySQL 8 的 caching_sha2_password
#                    挡成 Access denied —— 这是 MySQL 的安全设计，不是系统故障）
#   本机部署    -> 直接用本机 mysql 客户端连 3306
$mysql = 'C:\tools\mysql-8.0.29-winx64\bin\mysql.exe'

$dockerCli = (Get-Command docker -ErrorAction SilentlyContinue).Source
if (-not $dockerCli) {
    foreach ($p in @('E:\docker\resources\bin\docker.exe', 'C:\Program Files\Docker\Docker\resources\bin\docker.exe')) {
        if (Test-Path $p) { $dockerCli = $p; break }
    }
}

$useContainer = $false
if ($dockerCli) {
    $names = & $dockerCli ps --format '{{.Names}}' 2>$null
    if ($names -contains 'opsai-mysql') { $useContainer = $true }
}

function Invoke-Sql([string]$sql) {
    if ($useContainer) {
        return (& $dockerCli exec opsai-mysql mysql -uroot -proot123456 -N -B -e $sql 2>$null)
    }
    return (& $mysql -h 127.0.0.1 -P 3306 -u root -p123456 -N -B -e $sql 2>$null)
}

if ($useContainer) {
    Write-Host "  查询目标：Docker 容器 MySQL（docker exec opsai-mysql）"
} else {
    Write-Host "  查询目标：本机 MySQL (3306)"
}

try {
    $tables = Invoke-Sql "USE opsai; SHOW TABLES;"
    Check '七张表齐全（含自愈事件表）' ($tables.Count -eq 7) ($tables -join ', ')
    $vc = Invoke-Sql "USE opsai; SELECT COUNT(*) FROM kb_chunk WHERE embedding IS NOT NULL AND dim=1024;"
    Check 'kb_chunk 中有 1024 维向量' ([int]$vc -gt 0) "$vc 条向量记录"
    $rc = Invoke-Sql "USE opsai; SELECT COUNT(*) FROM diagnose_record;"
    Check '诊断过程已留痕 (diagnose_record)' ([int]$rc -gt 0) "$rc 条诊断记录"
    $uc = Invoke-Sql "USE opsai; SELECT COUNT(*) FROM sys_user WHERE password LIKE '`$2a`$%';"
    Check '初始账号已用 BCrypt 加密入库' ([int]$uc -ge 3) "$uc 个账号"
} catch { Check '数据库检查' $false $_.Exception.Message }

Write-Host "`n`n==================== 验收汇总 ====================" -ForegroundColor Yellow
$results | Format-Table -AutoSize -Wrap
$pass = ($results | Where-Object { $_.结果 -eq '通过' }).Count
$fail = ($results | Where-Object { $_.结果 -eq '未通过' }).Count
Write-Host ("通过 {0} 项，未通过 {1} 项，合计 {2} 项" -f $pass, $fail, $results.Count) -ForegroundColor $(if ($fail -eq 0) { 'Green' } else { 'Red' })
