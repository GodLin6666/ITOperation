<script setup>
/**
 * 普通用户（只读账号）的极简页。
 *
 * 定位：在自己电脑上遇到问题时，问一句 / 贴张截图，拿到能照做的处理建议就走。
 * 所以这里刻意没有任何表格、统计、知识库入口，只有「提问 → 回答」。
 * 完整的诊断过程（日志模板、向量、命中片段、耗时）都留在后台，运维人员查得到。
 */
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { assistApi, authApi } from '../api'

const user = ref(JSON.parse(localStorage.getItem('opsai_user') || 'null'))
const question = ref('')
const loading = ref(false)
const result = ref(null)
const chat = ref([])          // 追问对话
const followUpText = ref('')
const asking = ref(false)

/* ---------------- 截图 ---------------- */
const shot = ref(null)        // { preview, name, size, path }
const uploading = ref(false)
const dragOver = ref(false)

const samples = [
  '电脑很卡，打开什么都很慢',
  '连不上公司的共享打印机',
  '开机后某个服务没起来',
  'C 盘空间满了怎么办',
  '浏览器打不开网页，但 QQ 能上网'
]

async function handleFile(file) {
  if (!file) return
  if (!file.type.startsWith('image/')) {
    ElMessage.warning('请选择图片文件（PNG / JPG / WEBP）')
    return
  }
  if (file.size > 8 * 1024 * 1024) {
    ElMessage.warning('图片超过 8MB，请先压缩')
    return
  }
  uploading.value = true
  try {
    const res = await assistApi.uploadImage(file)
    shot.value = {
      preview: URL.createObjectURL(file),
      name: res.filename || file.name,
      size: file.size,
      path: res.path
    }
    ElMessage.success('截图已附上')
  } catch {
    /* 拦截器已提示 */
  } finally {
    uploading.value = false
  }
}

function clearShot() {
  if (shot.value?.preview) URL.revokeObjectURL(shot.value.preview)
  shot.value = null
}

function onPick(e) {
  handleFile(e.target.files?.[0])
  e.target.value = ''
}

/* 粘贴截图（Ctrl+V） */
function onPaste(e) {
  const items = e.clipboardData?.items || []
  for (const it of items) {
    if (it.type.startsWith('image/')) {
      handleFile(it.getAsFile())
      e.preventDefault()
      return
    }
  }
}

/* 拖拽截图 */
function onDrop(e) {
  dragOver.value = false
  handleFile(e.dataTransfer?.files?.[0])
}

/* ---------------- 提问 ---------------- */
async function ask() {
  const q = question.value.trim()
  if (!q && !shot.value) {
    ElMessage.warning('先说说遇到什么问题，或者贴一张截图')
    return
  }
  loading.value = true
  result.value = null
  chat.value = []
  try {
    result.value = await assistApi.ask({
      question: q,
      imagePath: shot.value?.path || null,
      title: q ? q.slice(0, 40) : '截图提问'
    })
    ElMessage.success('已给出建议')
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

async function sendFollowUp() {
  const t = followUpText.value.trim()
  if (!t || !result.value?.ticketId) return
  chat.value.push({ role: 'user', text: t })
  followUpText.value = ''
  asking.value = true
  try {
    const { default: api } = await import('../api')
    const res = await api.post('/diagnose/followup', { ticketId: result.value.ticketId, question: t })
    chat.value.push({ role: 'ai', text: res.answer })
  } catch (e) {
    chat.value.push({ role: 'ai', text: '没问出来，稍后再试一次。' })
  } finally {
    asking.value = false
  }
}

function reset() {
  result.value = null
  chat.value = []
  question.value = ''
  clearShot()
}

async function logout() {
  try { await authApi.logout() } catch { /* 忽略 */ }
  localStorage.removeItem('opsai_token')
  localStorage.removeItem('opsai_user')
  location.href = '/login'
}

onMounted(() => window.addEventListener('paste', onPaste))
onBeforeUnmount(() => {
  window.removeEventListener('paste', onPaste)
  clearShot()
})
</script>

<template>
  <div class="wrap">
    <!-- 顶部：极简，只显示身份和退出 -->
    <header class="bar">
      <div class="brand">
        <el-icon size="20"><Monitor /></el-icon>
        <span>桌面运维助手</span>
      </div>
      <div class="me">
        <span>{{ user?.realName || user?.username }}</span>
        <el-button link type="primary" size="small" @click="logout">退出</el-button>
      </div>
    </header>

    <main class="body">
      <!-- 提问区 -->
      <section v-if="!result" class="card">
        <h1>遇到什么问题？</h1>
        <p class="sub">说一句就行，能贴截图的话也贴上。系统会给你可能的原因和处理步骤。</p>

        <el-input
          v-model="question"
          type="textarea"
          :rows="4"
          resize="none"
          maxlength="500"
          show-word-limit
          placeholder="例如：电脑很卡，打开什么都慢；或者「连不上共享打印机，报错 0x0000011b」"
          class="q"
          @keydown.ctrl.enter="ask"
        />

        <div class="sample-row">
          <span class="sample-label">常见问题：</span>
          <el-tag
            v-for="s in samples"
            :key="s"
            class="sample"
            effect="plain"
            @click="question = s"
          >{{ s }}</el-tag>
        </div>

        <!-- 截图区 -->
        <div
          class="shot-box"
          :class="{ over: dragOver, have: !!shot }"
          @dragover.prevent="dragOver = true"
          @dragleave.prevent="dragOver = false"
          @drop.prevent="onDrop"
        >
          <template v-if="!shot">
            <el-upload
              :show-file-list="false"
              :auto-upload="false"
              accept="image/*"
              :on-change="(f) => handleFile(f.raw)"
            >
              <el-button :loading="uploading">
                <el-icon><Camera /></el-icon>&nbsp;贴张截图（可选）
              </el-button>
            </el-upload>
            <span class="shot-hint">也可以直接 Ctrl+V 粘贴，或把图片拖进来</span>
          </template>
          <template v-else>
            <img :src="shot.preview" class="thumb" alt="截图预览" />
            <div class="shot-info">
              <div class="shot-name">{{ shot.name }}</div>
              <div class="shot-tip">截图会一起提交，运维人员能在后台复核</div>
            </div>
            <el-button link type="danger" @click="clearShot">移除</el-button>
          </template>
        </div>

        <el-button
          type="primary"
          size="large"
          class="submit"
          :loading="loading"
          @click="ask"
        >
          {{ loading ? '正在分析…（本地模型约 20~40 秒）' : '帮我看看' }}
        </el-button>
      </section>

      <!-- 回答区 -->
      <section v-else class="card result">
        <div class="q-recap">
          <el-icon><ChatDotRound /></el-icon>
          <span>{{ question || '（截图提问）' }}</span>
        </div>

        <!-- 只看结论 -->
        <el-alert
          type="success"
          :closable="false"
          show-icon
          class="conclusion"
          :title="result.conclusion || '已给出排查建议'"
        />

        <!-- 可能原因：一次只展示最多 3 条 -->
        <div v-for="(c, i) in result.causes || []" :key="i" class="cause">
          <div class="cause-head">
            <span class="idx">{{ i + 1 }}</span>
            <span class="cause-title">{{ c.cause }}</span>
            <el-tag size="small" :type="c.confidence >= 0.7 ? 'danger' : 'info'">
              把握 {{ Math.round((c.confidence || 0) * 100) }}%
            </el-tag>
          </div>

          <ol v-if="c.steps?.length" class="steps">
            <li v-for="(s, j) in c.steps" :key="j">{{ s }}</li>
          </ol>

          <div v-if="c.fix" class="fix">
            <el-icon><Tools /></el-icon>
            <span>{{ c.fix }}</span>
          </div>
        </div>

        <!-- 还需要补充的信息 -->
        <div v-if="result.needMoreInfo?.length" class="more">
          <div class="more-title">还需要你补充：</div>
          <ul>
            <li v-for="(m, i) in result.needMoreInfo" :key="i">{{ m }}</li>
          </ul>
        </div>

        <!-- 参考资料：只给标题，不给全文 -->
        <div v-if="result.sources?.length" class="sources">
          <span class="src-label">参考了：</span>
          <el-tag v-for="(s, i) in result.sources" :key="i" size="small" effect="plain" class="src">
            {{ s.title }}
          </el-tag>
        </div>

        <!-- 追问 -->
        <div class="chat">
          <div v-for="(m, i) in chat" :key="i" class="bubble" :class="m.role">
            <div class="bubble-body">{{ m.text }}</div>
          </div>
          <div class="ask-row">
            <el-input
              v-model="followUpText"
              placeholder="还是没解决？继续问…"
              @keyup.enter="sendFollowUp"
            />
            <el-button type="primary" :loading="asking" @click="sendFollowUp">追问</el-button>
          </div>
        </div>

        <div class="actions">
          <el-button @click="reset">问下一个问题</el-button>
        </div>
      </section>
    </main>

    <footer class="foot">本地部署 · 不联网 · 所有数据只留在本机</footer>
  </div>
</template>

<style scoped>
.wrap {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: #f5f7fa;
}

/* 顶部条 */
.bar {
  height: 52px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
}
.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  color: #303133;
}
.me {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: #606266;
}

/* 主体 */
.body {
  flex: 1;
  display: flex;
  justify-content: center;
  padding: 28px 16px;
}

.card {
  width: 100%;
  max-width: 720px;
  background: #fff;
  border-radius: 12px;
  padding: 28px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.04);
  height: fit-content;
}

h1 {
  margin: 0 0 6px;
  font-size: 22px;
  color: #303133;
}
.sub {
  margin: 0 0 18px;
  color: #909399;
  font-size: 13px;
}

.q :deep(textarea) {
  font-size: 15px;
  line-height: 1.7;
}

.sample-row {
  margin: 12px 0 4px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
.sample-label {
  font-size: 12px;
  color: #909399;
}
.sample {
  cursor: pointer;
}

/* 截图区 */
.shot-box {
  margin: 16px 0;
  border: 1px dashed #dcdfe6;
  border-radius: 8px;
  padding: 16px;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  transition: all 0.2s;
}
.shot-box.over {
  border-color: #409eff;
  background: #ecf5ff;
}
.shot-box.have {
  border-style: solid;
}
.shot-hint {
  font-size: 12px;
  color: #a8abb2;
}
.thumb {
  width: 88px;
  height: 88px;
  object-fit: cover;
  border-radius: 6px;
  border: 1px solid #ebeef5;
}
.shot-info {
  flex: 1;
  min-width: 140px;
}
.shot-name {
  font-size: 13px;
  color: #303133;
  word-break: break-all;
}
.shot-tip {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.submit {
  width: 100%;
  margin-top: 8px;
}

/* 回答 */
.q-recap {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #606266;
  font-size: 14px;
  padding: 10px 12px;
  background: #f5f7fa;
  border-radius: 8px;
  margin-bottom: 16px;
}
.conclusion {
  margin-bottom: 18px;
}

.cause {
  border: 1px solid #ebeef5;
  border-radius: 10px;
  padding: 16px;
  margin-bottom: 14px;
}
.cause-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.idx {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #409eff;
  color: #fff;
  font-size: 12px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
}
.cause-title {
  flex: 1;
  font-weight: 600;
  color: #303133;
}
.steps {
  margin: 0 0 10px;
  padding-left: 22px;
  color: #606266;
  line-height: 1.9;
  font-size: 14px;
}
.fix {
  display: flex;
  gap: 8px;
  background: #f0f9eb;
  color: #529b2e;
  border-radius: 6px;
  padding: 10px 12px;
  font-size: 13px;
  line-height: 1.7;
}

.more {
  background: #fdf6ec;
  border-radius: 8px;
  padding: 12px 14px;
  margin-bottom: 14px;
  font-size: 13px;
  color: #b88230;
}
.more-title {
  font-weight: 600;
  margin-bottom: 6px;
}
.more ul {
  margin: 0;
  padding-left: 18px;
}

.sources {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-bottom: 18px;
}
.src-label {
  font-size: 12px;
  color: #909399;
}

/* 追问 */
.chat {
  border-top: 1px solid #f0f2f5;
  padding-top: 16px;
}
.bubble {
  margin-bottom: 10px;
}
.bubble-body {
  padding: 10px 12px;
  border-radius: 8px;
  line-height: 1.8;
  font-size: 14px;
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
.ask-row {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}
.actions {
  margin-top: 18px;
  text-align: right;
}

.foot {
  text-align: center;
  font-size: 12px;
  color: #c0c4cc;
  padding: 14px 0 20px;
}

@media (max-width: 640px) {
  .card {
    padding: 18px;
  }
  h1 {
    font-size: 19px;
  }
}
</style>
