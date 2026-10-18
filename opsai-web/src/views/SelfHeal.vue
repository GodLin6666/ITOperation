<script setup>
/**
 * 自愈中心（运维管理端）。
 *
 * 这是整个系统里唯一会「动手改东西」的页面，所以设计上刻意做成：
 *   巡检发现问题 → 展示证据 → 给出白名单动作和 dry-run 预览 → 管理员确认 → 执行 → 复检
 *
 * 界面上永远能看到"系统打算做什么"，不存在"悄悄执行"。
 */
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { selfHealApi } from '../api'

const metrics = ref(null)
const events = ref([])
const actions = ref([])
const loading = ref(false)
const scanning = ref(false)

const drawer = ref(false)
const current = ref(null)
const busy = ref('')          // 'diagnose' | 'execute' | 'ignore'

const user = ref(JSON.parse(localStorage.getItem('opsai_user') || 'null'))
const isAdmin = computed(() => user.value?.role === 'admin')

/* ---------------- 状态与配色 ---------------- */
const STATUS = {
  DETECTED:  { text: '已发现',   type: 'warning' },
  DIAGNOSED: { text: '已诊断',   type: 'primary' },
  PROPOSED:  { text: '待确认',   type: 'danger'  },
  EXECUTED:  { text: '已执行',   type: 'primary' },
  VERIFIED:  { text: '已恢复',   type: 'success' },
  FAILED:    { text: '未恢复',   type: 'danger'  },
  IGNORED:   { text: '已忽略',   type: 'info'    }
}
const statusText = (s) => STATUS[s]?.text || s
const statusType = (s) => STATUS[s]?.type || 'info'

const SEVERITY = {
  LOW:    { text: '低', type: 'info'    },
  MEDIUM: { text: '中', type: 'warning' },
  HIGH:   { text: '高', type: 'danger'  }
}
const sevText = (s) => SEVERITY[s]?.text || s
const sevType = (s) => SEVERITY[s]?.type || 'info'

const canExecute = (e) => e.status === 'PROPOSED' && !!e.actionKey && isAdmin.value
const canIgnore = (e) => ['DETECTED', 'DIAGNOSED', 'PROPOSED'].includes(e.status)

/* ---------------- 数据加载 ---------------- */
async function loadMetrics() {
  try { metrics.value = await selfHealApi.metrics() } catch { /* 拦截器已提示 */ }
}
async function loadEvents() {
  try { events.value = await selfHealApi.events() } catch { /* ignore */ }
}
async function reload() {
  loading.value = true
  try { await Promise.all([loadMetrics(), loadEvents()]) } finally { loading.value = false }
}

async function doScan() {
  scanning.value = true
  try {
    const r = await selfHealApi.scan()
    ElMessage.success(r.created > 0 ? `发现 ${r.created} 个新问题` : '巡检完成，各项指标正常')
    await reload()
  } finally {
    scanning.value = false
  }
}

/* ---------------- 事件操作 ---------------- */
function openDetail(row) {
  current.value = row
  drawer.value = true
}

async function doDiagnose() {
  busy.value = 'diagnose'
  try {
    current.value = await selfHealApi.diagnose(current.value.id)
    ElMessage.success('AI 分析完成')
    await loadEvents()
  } finally {
    busy.value = ''
  }
}

async function doExecute() {
  const e = current.value
  try {
    await ElMessageBox.confirm(
      `即将执行：${actionTitle(e.actionKey)}\n\n${e.actionPlan || ''}\n\n执行后会立刻复检，确认指标是否恢复。确定继续？`,
      '确认执行处置动作',
      { type: 'warning', confirmButtonText: '确认执行', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  busy.value = 'execute'
  try {
    const r = await selfHealApi.execute(e.id)
    current.value = r
    if (r.status === 'VERIFIED') {
      ElMessage.success('执行成功，复检通过')
    } else {
      ElMessage.warning('执行完成，但复检未通过，需要人工介入')
    }
    await reload()
  } finally {
    busy.value = ''
  }
}

async function doIgnore() {
  try {
    await ElMessageBox.confirm('确定忽略这条事件吗？', '提示', { type: 'info' })
  } catch {
    return
  }
  busy.value = 'ignore'
  try {
    current.value = await selfHealApi.ignore(current.value.id)
    ElMessage.success('已忽略')
    await loadEvents()
  } finally {
    busy.value = ''
  }
}

/* ---------------- 展示工具 ---------------- */
function actionTitle(key) {
  const a = actions.value.find((x) => x.key === key)
  return a ? a.title : key || '（无）'
}

const fmtTime = (t) => (t ? String(t).replace('T', ' ').slice(0, 19) : '-')

/** 诊断内容是 JSON 或纯文本，展示时做兼容 */
const diagnosisView = computed(() => {
  const d = current.value?.diagnosis
  if (!d) return null
  try {
    const o = JSON.parse(d)
    if (o && o.summary) return o
  } catch { /* 不是 JSON，按纯文本处理 */ }
  return { summary: d, causes: [] }
})

const healthColor = (pct, threshold) => {
  if (pct >= 95) return '#f56c6c'
  if (pct >= threshold) return '#e6a23c'
  return '#67c23a'
}

onMounted(async () => {
  await reload()
  try { actions.value = await selfHealApi.actions() } catch { /* ignore */ }
})
</script>

<template>
  <div class="page" v-loading="loading">
    <!-- ============ 一、健康指标 ============ -->
    <div class="metric-row">
      <!-- 磁盘 -->
      <el-card shadow="never" class="metric">
        <div class="m-label">磁盘使用率</div>
        <div class="m-value" :style="{ color: healthColor(metrics?.disk?.usedPct || 0, metrics?.disk?.threshold || 85) }">
          {{ metrics?.disk?.usedPct ?? '-' }}%
        </div>
        <div class="m-sub">可用 {{ metrics?.disk?.usableHuman || '-' }} / 共 {{ metrics?.disk?.totalHuman || '-' }}</div>
      </el-card>

      <!-- 内存 -->
      <el-card shadow="never" class="metric">
        <div class="m-label">服务内存</div>
        <div class="m-value" :style="{ color: healthColor(metrics?.memory?.usedPct || 0, 90) }">
          {{ metrics?.memory?.usedPct ?? '-' }}%
        </div>
        <div class="m-sub">{{ metrics?.memory?.usedHuman || '-' }} / {{ metrics?.memory?.maxHuman || '-' }}</div>
      </el-card>

      <!-- Ollama -->
      <el-card shadow="never" class="metric">
        <div class="m-label">本地模型</div>
        <div class="m-value" :style="{ color: metrics?.ollama?.up ? '#67c23a' : '#f56c6c' }">
          {{ metrics?.ollama?.up ? '在线' : '离线' }}
        </div>
        <div class="m-sub">
          对话{{ metrics?.ollama?.chatModelReady ? '✓' : '✗' }}
          向量{{ metrics?.ollama?.embedModelReady ? '✓' : '✗' }}
        </div>
      </el-card>

      <!-- 知识库 -->
      <el-card shadow="never" class="metric">
        <div class="m-label">知识库</div>
        <div class="m-value" :style="{ color: (metrics?.kb?.documents || 0) > 0 ? '#67c23a' : '#f56c6c' }">
          {{ metrics?.kb?.documents ?? '-' }} 篇
        </div>
        <div class="m-sub">{{ metrics?.kb?.chunks ?? 0 }} 个向量块</div>
      </el-card>

      <!-- 上传目录 -->
      <el-card shadow="never" class="metric">
        <div class="m-label">上传占用</div>
        <div class="m-value">{{ metrics?.upload?.sizeHuman || '-' }}</div>
        <div class="m-sub">{{ metrics?.upload?.files ?? 0 }} 个文件</div>
      </el-card>
    </div>

    <!-- ============ 二、操作栏 ============ -->
    <el-card shadow="never" class="mt">
      <div class="toolbar">
        <el-button type="primary" :loading="scanning" @click="doScan">
          <el-icon><Refresh /></el-icon>&nbsp;立即巡检
        </el-button>
        <el-button @click="reload">刷新数据</el-button>

        <div class="spacer" />

        <span class="hint">
          待处理 <b class="warn">{{ metrics?.openEvents ?? 0 }}</b> 条 ·
          白名单动作 {{ actions.length }} 个
        </span>
      </div>

      <el-alert type="info" :closable="false" show-icon class="rule-tip">
        <template #title>
          自愈采用「巡检 → 诊断 → 人工确认 → 执行 → 复检」流程：
          <b>系统只会执行白名单里的动作</b>，大模型给出的命令不会被自动执行；所有处置都需要管理员确认。
        </template>
      </el-alert>
    </el-card>

    <!-- ============ 三、事件列表 ============ -->
    <el-card shadow="never" class="mt">
      <template #header>
        <span class="card-title">自愈事件</span>
        <span class="card-sub">共 {{ events.length }} 条</span>
      </template>

      <el-table :data="events" border stripe size="small" max-height="480"
                empty-text="暂无事件，点「立即巡检」检查当前状态">
        <el-table-column label="发现时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.detectTime) }}</template>
        </el-table-column>
        <el-table-column prop="title" label="问题" min-width="180" show-overflow-tooltip />
        <el-table-column label="等级" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="sevType(row.severity)" size="small" effect="dark">{{ sevText(row.severity) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="指标" width="150">
          <template #default="{ row }">
            <span class="mono">{{ row.metricName }} = {{ row.metricValue }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small" effect="plain">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="处置动作" width="170">
          <template #default="{ row }">
            <span v-if="row.actionKey" class="action-key">{{ actionTitle(row.actionKey) }}</span>
            <span v-else class="muted">需人工处理</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- ============ 四、详情抽屉 ============ -->
    <el-drawer v-model="drawer" title="自愈事件详情" size="560px">
      <template v-if="current">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="问题">{{ current.title }}</el-descriptions-item>
          <el-descriptions-item label="规则">{{ current.ruleKey }}</el-descriptions-item>
          <el-descriptions-item label="等级">
            <el-tag :type="sevType(current.severity)" size="small" effect="dark">{{ sevText(current.severity) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="实测 / 阈值">
            {{ current.metricName }} = {{ current.metricValue }}（阈值 {{ current.threshold }}）
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusType(current.status)" size="small">{{ statusText(current.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="发现时间">{{ fmtTime(current.detectTime) }}</el-descriptions-item>
          <el-descriptions-item v-if="current.executeTime" label="执行时间">{{ fmtTime(current.executeTime) }}</el-descriptions-item>
          <el-descriptions-item v-if="current.operator" label="操作人">{{ current.operator }}</el-descriptions-item>
        </el-descriptions>

        <!-- 诊断 -->
        <div class="sec">
          <div class="sec-title">
            <span>问题说明 / AI 诊断</span>
            <el-button
              link type="primary" size="small"
              :loading="busy === 'diagnose'"
              @click="doDiagnose"
            >{{ busy === 'diagnose' ? '分析中…（约 30 秒）' : 'AI 分析原因' }}</el-button>
          </div>
          <div v-if="diagnosisView" class="diag">
            <p class="diag-summary">{{ diagnosisView.summary }}</p>
            <div v-for="(c, i) in diagnosisView.causes || []" :key="i" class="diag-cause">
              <span class="diag-idx">{{ i + 1 }}</span>
              <div>
                <div class="diag-cause-title">{{ c.cause }}</div>
                <div class="diag-fix">{{ c.fix }}</div>
              </div>
            </div>
          </div>
          <el-empty v-else description="还没有诊断信息" :image-size="60" />
        </div>

        <!-- 处置方案（dry-run） -->
        <div class="sec">
          <div class="sec-title">
            <span>处置方案（执行前预览）</span>
            <el-tag v-if="current.actionKey" size="small" type="warning" effect="plain">
              {{ actionTitle(current.actionKey) }}
            </el-tag>
          </div>
          <pre v-if="current.actionPlan" class="plan">{{ current.actionPlan }}</pre>
          <el-alert v-else type="warning" :closable="false" show-icon
                    title="该问题没有可自动处置的白名单动作，需要人工处理" />
        </div>

        <!-- 执行结果 -->
        <div v-if="current.actionResult" class="sec">
          <div class="sec-title"><span>执行结果</span></div>
          <pre class="plan">{{ current.actionResult }}</pre>
        </div>

        <!-- 底部按钮 -->
        <div class="drawer-actions">
          <el-button
            type="danger"
            :disabled="!canExecute(current)"
            :loading="busy === 'execute'"
            @click="doExecute"
          >确认执行</el-button>
          <el-button
            :disabled="!canIgnore(current)"
            :loading="busy === 'ignore'"
            @click="doIgnore"
          >忽略</el-button>
          <span class="drawer-hint">
            <template v-if="!isAdmin">只有管理员能执行处置动作</template>
            <template v-else-if="!current.actionKey">该事件无白名单动作</template>
          </span>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.page {
  max-width: 1400px;
  margin: 0 auto;
}
.mt {
  margin-top: 12px;
}

/* 指标卡 */
.metric-row {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 12px;
}
.metric :deep(.el-card__body) {
  padding: 14px 16px;
}
.m-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 6px;
}
.m-value {
  font-size: 24px;
  font-weight: 700;
  color: #303133;
  line-height: 1.2;
}
.m-sub {
  font-size: 12px;
  color: #a8abb2;
  margin-top: 4px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
}
.spacer {
  flex: 1;
}
.hint {
  font-size: 13px;
  color: #606266;
}
.warn {
  color: #e6a23c;
  font-size: 15px;
}
.rule-tip {
  margin-top: 12px;
}

.card-title {
  font-weight: 600;
  color: #303133;
}
.card-sub {
  font-size: 12px;
  color: #909399;
  margin-left: 8px;
}

.mono {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  color: #606266;
}
.action-key {
  font-size: 12px;
  color: #409eff;
}
.muted {
  font-size: 12px;
  color: #c0c4cc;
}

/* 抽屉 */
.sec {
  margin-top: 20px;
}
.sec-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
  color: #303133;
  font-size: 14px;
  margin-bottom: 8px;
}
.diag-summary {
  margin: 0 0 10px;
  color: #606266;
  line-height: 1.8;
  font-size: 13px;
}
.diag-cause {
  display: flex;
  gap: 8px;
  padding: 10px 12px;
  background: #f5f7fa;
  border-radius: 6px;
  margin-bottom: 8px;
}
.diag-idx {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #409eff;
  color: #fff;
  font-size: 12px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  margin-top: 2px;
}
.diag-cause-title {
  font-weight: 600;
  font-size: 13px;
  color: #303133;
}
.diag-fix {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
  line-height: 1.7;
}
.plan {
  background: #1f2d3d;
  color: #d7dde5;
  border-radius: 8px;
  padding: 14px;
  font-size: 12.5px;
  line-height: 1.9;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
  font-family: Consolas, Monaco, monospace;
}
.drawer-actions {
  margin-top: 24px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding-top: 16px;
  border-top: 1px solid #f0f2f5;
}
.drawer-hint {
  font-size: 12px;
  color: #a8abb2;
}

@media (max-width: 1100px) {
  .metric-row {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
