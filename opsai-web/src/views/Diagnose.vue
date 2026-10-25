<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { diagApi, caseApi } from '../api'

const loading = ref(false)
const result = ref(null)
const form = ref({
  title: '',
  platform: 'auto',
  deviceType: 'db',
  faultCategory: 'db',
  phenomenon: '',
  logText: ''
})

// auto 交给后端按日志特征判断；选错平台会过滤掉另一边的知识，所以默认自动
const platformOptions = [
  { label: '自动识别', value: 'auto' },
  { label: 'Windows 桌面', value: 'windows' },
  { label: 'Linux 服务器', value: 'linux' }
]

const deviceTypes = [
  { label: '桌面电脑', value: 'desktop' },
  { label: '服务器', value: 'server' },
  { label: '数据库', value: 'db' },
  { label: '网络', value: 'network' },
  { label: '中间件', value: 'middleware' },
  { label: '应用', value: 'app' }
]

const categories = [
  { label: 'CPU', value: 'cpu' },
  { label: '内存', value: 'mem' },
  { label: '磁盘', value: 'disk' },
  { label: '网络', value: 'network' },
  { label: '数据库', value: 'db' },
  { label: '中间件', value: 'middleware' },
  { label: '系统', value: 'system' },
  { label: '应用', value: 'app' }
]

/* ---------- 追问 ---------- */
const question = ref('')
const asking = ref(false)
const chat = ref([]) // { role:'user'|'ai', text }

/* ---------- 演示用的一键填充 ---------- */
const sample = {
  title: '订单服务连接数据库超时',
  platform: 'linux',
  deviceType: 'db',
  faultCategory: 'db',
  phenomenon: '订单服务从 10:12 开始大量报错，接口 500，重启无效',
  logText: `2026-10-09 10:12:31 ERROR [order-service] com.zaxxer.hikari.HikariPool - Connection is not available, request timed out after 30000ms
2026-10-09 10:12:32 ERROR [order-service] java.sql.SQLNonTransientConnectionException: Data source rejected establishment of connection, message from server: "Too many connections"
2026-10-09 10:12:33 WARN  [order-service] HikariPool-1 - Pool stats (total=50, active=50, idle=0, waiting=12)
2026-10-09 10:12:35 ERROR [order-service] Timeout waiting for connection from pool`
}

/* ---------- 桌面场景的一键填充 ---------- */
const desktopSample = {
  title: '员工电脑开机后无法打印且共享盘打不开',
  platform: 'windows',
  deviceType: 'desktop',
  faultCategory: 'system',
  phenomenon: '财务部电脑今早开机后打印机显示脱机，访问共享盘提示找不到网络路径，重启过两次无效',
  logText: [
    '2026-10-23 08:41:12 System   ID=7000  Print Spooler 服务因下列错误而无法启动: 拒绝访问',
    '2026-10-23 08:41:30 System   ID=7031  Print Spooler 服务意外终止，将尝试重启',
    '2026-10-23 08:42:05 System   ID=5719  此计算机无法与域控制器建立安全会话',
    '2026-10-23 08:43:11 Application ID=1000  explorer.exe 崩溃，模块名称: ntdll.dll'
  ].join('\n')
}

function platformLabel(p) {
  if (!p || p === 'ANY') return '通用'
  if (p === 'LINUX') return 'Linux 服务器'
  if (p === 'WINDOWS') return 'Windows 桌面'
  return p
}

function fillDesktopSample() {
  form.value = { ...desktopSample }
  ElMessage.success('已填入桌面故障示例，可以直接点「开始诊断」')
}

function fillSample() {
  form.value = { ...sample }
  ElMessage.success('已填入示例日志，可以直接点「开始诊断」')
}

/* ---------- 上传日志文件 ---------- */
const uploading = ref(false)

async function onUpload(opt) {
  const file = opt.file
  const fd = new FormData()
  fd.append('file', file)
  uploading.value = true
  try {
    const res = await fetch('/api/upload/log', {
      method: 'POST',
      headers: { 'X-Token': localStorage.getItem('opsai_token') || '' },
      body: fd
    })
    if (!res.ok) throw new Error('上传失败：' + res.status)
    const data = await res.json()
    form.value.logText = data.content
    if (!form.value.title) form.value.title = data.filename
    ElMessage.success(`已载入 ${data.filename}（${(data.size / 1024).toFixed(1)} KB）`)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    uploading.value = false
    opt.onComplete?.()
  }
}

/* ---------- 开始诊断 ---------- */
async function doDiagnose() {
  if (!form.value.phenomenon && !form.value.logText) {
    ElMessage.warning('请至少填写现象描述或粘贴日志')
    return
  }
  loading.value = true
  result.value = null
  chat.value = []
  try {
    result.value = await diagApi.diagnose(form.value)
    ElMessage.success('诊断完成')
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

/* ---------- 追问 ---------- */
async function doAsk() {
  if (!question.value.trim()) {
    ElMessage.warning('请输入追问内容')
    return
  }
  if (!result.value?.ticketId) {
    ElMessage.warning('请先完成一次诊断')
    return
  }
  const q = question.value.trim()
  chat.value.push({ role: 'user', text: q })
  question.value = ''
  asking.value = true
  try {
    const res = await diagApi.followUp(result.value.ticketId, q)
    chat.value.push({
      role: 'ai',
      text: res.answer,
      meta: `首字 ${res.firstTokenMs} ms｜总耗时 ${res.costMs} ms`
    })
  } catch {
    chat.value.push({ role: 'ai', text: '追问失败，请稍后重试。' })
  } finally {
    asking.value = false
  }
}

/* ---------- 成果动作 ---------- */
const adopted = ref(false)
const adopting = ref(false)

async function onAdopt() {
  adopting.value = true
  try {
    await caseApi.adopt(result.value.ticketId, true)
    adopted.value = true
    ElMessage.success('已标记为采纳，统计页的采纳率会更新')
  } finally {
    adopting.value = false
  }
}

function onExport() {
  window.open(caseApi.exportUrl(result.value.ticketId), '_blank')
  ElMessage.info('处理单已开始下载')
}

onMounted(() => {
  // 页面打开时不做任何请求，避免白白占用显存
})

const fmtMs = (v) => (v == null ? '-' : v + ' ms')
</script>

<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span>故障信息录入</span>
          <span>
            <el-button link type="primary" @click="fillSample">填入服务器示例</el-button>
            <el-button link type="primary" @click="fillDesktopSample">填入桌面示例</el-button>
          </span>
        </div>
      </template>

      <el-form label-position="top">
        <el-row :gutter="12">
          <el-col :xs="24" :sm="12" :md="6">
            <el-form-item label="故障标题">
              <el-input v-model="form.title" placeholder="例如：订单服务连接数据库超时" />
            </el-form-item>
          </el-col>
          <el-col :xs="12" :sm="6" :md="4">
            <el-form-item label="平台">
              <el-select v-model="form.platform" style="width: 100%">
                <el-option v-for="p in platformOptions" :key="p.value" :label="p.label" :value="p.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="12" :sm="6" :md="3">
            <el-form-item label="设备类型">
              <el-select v-model="form.deviceType" style="width: 100%">
                <el-option v-for="d in deviceTypes" :key="d.value" :label="d.label" :value="d.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="12" :sm="6" :md="3">
            <el-form-item label="故障类别">
              <el-select v-model="form.faultCategory" style="width: 100%">
                <el-option v-for="c in categories" :key="c.value" :label="c.label" :value="c.value" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="故障现象描述">
          <el-input v-model="form.phenomenon" type="textarea" :rows="3"
                    placeholder="例如：订单服务从 10:12 开始大量报错，接口 500，重启无效" />
        </el-form-item>

        <el-form-item>
          <template #label>
            <div class="label-row">
              <span>日志文本</span>
              <el-upload :show-file-list="false" :auto-upload="false" :on-change="onUpload" accept=".log,.txt,.out">
                <el-button size="small" :loading="uploading">
                  <el-icon><Upload /></el-icon>&nbsp;上传日志文件
                </el-button>
              </el-upload>
            </div>
          </template>
          <el-input v-model="form.logText" type="textarea" :rows="10" class="mono"
                    placeholder="把日志粘贴到这里，或点上面的按钮上传 .log / .txt 文件" />
        </el-form-item>

        <el-button type="primary" size="large" :loading="loading" @click="doDiagnose">
          {{ loading ? '诊断中，请稍候…（本地大模型推理约需 20 秒）' : '开始诊断' }}
        </el-button>
      </el-form>
    </el-card>

    <!-- ============ 诊断结果 ============ -->
    <template v-if="result">
      <el-card shadow="never" class="mt">
        <el-alert type="success" :closable="false" show-icon
                  :title="result.answer?.summary || '诊断完成'" class="summary" />

        <el-descriptions :column="4" border size="small" class="mt-s">
          <el-descriptions-item label="故障单号">{{ result.ticketId }}</el-descriptions-item>
          <el-descriptions-item label="识别平台">
            <el-tag size="small" :type="result.platform === 'LINUX' ? 'success' : (result.platform === 'WINDOWS' ? 'warning' : 'info')">
              {{ platformLabel(result.platform) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="首字耗时">
            <el-tag :type="result.firstTokenMs <= 5000 ? 'success' : 'warning'" size="small">
              {{ fmtMs(result.firstTokenMs) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="总耗时">{{ fmtMs(result.costMs) }}</el-descriptions-item>
          <el-descriptions-item label="检索命中">{{ result.hits?.length || 0 }} 条</el-descriptions-item>
        </el-descriptions>

        <div class="actions">
          <el-button type="success" :disabled="adopted" :loading="adopting" @click="onAdopt">
            <el-icon><Select /></el-icon>&nbsp;{{ adopted ? '已标记采纳' : '标记建议已采纳' }}
          </el-button>
          <el-button @click="onExport">
            <el-icon><Download /></el-icon>&nbsp;导出处理单 (Word)
          </el-button>
        </div>
      </el-card>

      <!-- 可能原因 -->
      <el-card v-for="(c, i) in result.answer?.causes || []" :key="i" shadow="never" class="mt">
        <template #header>
          <div class="cause-head">
            <span class="cause-no">{{ i + 1 }}</span>
            <span class="cause-title">{{ c.cause }}</span>
            <el-tag size="small" :type="c.confidence >= 0.7 ? 'danger' : 'warning'">
              可信度 {{ c.confidence }}
            </el-tag>
          </div>
        </template>

        <p class="evidence"><b>依据：</b>{{ c.evidence }}</p>

        <p class="block-title"><b>排查步骤</b></p>
        <ol class="step-list">
          <li v-for="(s, j) in c.checkSteps || []" :key="j">{{ s }}</li>
        </ol>

        <el-alert type="info" :closable="false" class="mt-s" :title="'处置建议：' + c.fix" />
      </el-card>

      <el-card v-if="result.answer?.needMoreInfo?.length" shadow="never" class="mt">
        <template #header>还需要补充的信息</template>
        <ul class="step-list">
          <li v-for="(m, i) in result.answer.needMoreInfo" :key="i">{{ m }}</li>
        </ul>
      </el-card>

      <!-- 参考依据 -->
      <el-card shadow="never" class="mt">
        <template #header>参考依据（知识库检索命中）</template>
        <el-empty v-if="!result.hits?.length" description="没有命中任何知识，模型只能凭通用经验作答" />
        <div v-for="(h, i) in result.hits || []" :key="i" class="hit">
          <div class="hit-head">
            <el-tag size="small">资料{{ i + 1 }}</el-tag>
            <b>{{ h.title }}</b>
            <el-tag size="small" type="success">相似度 {{ Number(h.score).toFixed(4) }}</el-tag>
            <el-tag size="small" type="info">{{ h.deviceType }} / {{ h.category }}</el-tag>
          </div>
          <div class="hit-content">{{ h.content }}</div>
        </div>
      </el-card>

      <!-- 预处理过程 -->
      <el-card shadow="never" class="mt">
        <el-collapse>
          <el-collapse-item title="查看日志预处理过程（模板提取 + 关键词）">
            <p><b>关键词：</b>{{ (result.keywords || []).join('、') }}</p>
            <p class="muted">
              原始日志 {{ result.rawLineCount }} 行，实际送入模型 {{ result.usedLineCount }} 行
              （超出部分按 ERROR/WARN 优先保留，避免超出上下文长度）
            </p>
            <el-table :data="result.templates || []" size="small" border>
              <el-table-column type="index" label="#" width="50" />
              <el-table-column prop="pattern" label="日志模板" show-overflow-tooltip />
              <el-table-column prop="count" label="出现次数" width="90" align="center" />
            </el-table>
          </el-collapse-item>
        </el-collapse>
      </el-card>

      <!-- 追问 -->
      <el-card shadow="never" class="mt">
        <template #header>追问（在本次诊断结论上继续对话）</template>

        <div v-for="(m, i) in chat" :key="i" class="bubble" :class="m.role">
          <div class="bubble-body">{{ m.text }}</div>
          <div v-if="m.meta" class="bubble-meta">{{ m.meta }}</div>
        </div>
        <el-empty v-if="!chat.length" :image-size="60"
                  description="例如：「第 3 步的 show processlist 具体怎么看？」" />

        <div class="ask">
          <el-input v-model="question" placeholder="针对上面的结论继续提问…" @keyup.enter="doAsk" />
          <el-button type="primary" :loading="asking" @click="doAsk">追问</el-button>
        </div>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.page {
  max-width: 1000px;
  margin: 0 auto;
}

.mt {
  margin-top: 16px;
}

.mt-s {
  margin-top: 12px;
}

.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.label-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.summary {
  margin-bottom: 4px;
}

.actions {
  margin-top: 16px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.cause-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.cause-no {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #409eff;
  color: #fff;
  font-size: 12px;
}

.cause-title {
  flex: 1;
  font-weight: 600;
}

.evidence,
.block-title {
  line-height: 1.8;
  margin: 0 0 6px;
}

.hit {
  padding: 10px 0;
  border-bottom: 1px dashed #e4e7ed;
}

.hit:last-child {
  border-bottom: none;
}

.hit-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.muted {
  color: #909399;
  font-size: 12px;
  margin: 6px 0;
}

.bubble {
  margin: 10px 0;
}

.bubble-body {
  padding: 10px 12px;
  border-radius: 8px;
  line-height: 1.8;
  white-space: pre-wrap;
}

.bubble.user .bubble-body {
  background: #ecf5ff;
  border-left: 3px solid #409eff;
}

.bubble.ai .bubble-body {
  background: #f4f4f5;
  border-left: 3px solid #67c23a;
}

.bubble-meta {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.ask {
  display: flex;
  gap: 8px;
  margin-top: 14px;
}
</style>
