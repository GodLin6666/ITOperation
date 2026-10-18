<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { caseApi } from '../api'

const loading = ref(false)
const rows = ref([])
const keyword = ref('')

const statusMap = {
  0: { t: '待诊断', type: 'info' },
  1: { t: '已诊断', type: 'warning' },
  2: { t: '已处理', type: 'success' },
  3: { t: '已关闭', type: 'info' }
}

const filtered = computed(() =>
  rows.value.filter((r) => !keyword.value || (r.title || '').includes(keyword.value))
)

/* ---------- 详情抽屉 ---------- */
const drawer = ref(false)
const detail = ref(null)
const detailLoading = ref(false)

async function openDetail(row) {
  drawer.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await caseApi.detail(row.id)
  } finally {
    detailLoading.value = false
  }
}

async function onAdopt(ticketId) {
  await caseApi.adopt(ticketId, true)
  ElMessage.success('已标记采纳')
  await load()
  if (detail.value?.ticket?.id === ticketId) {
    detail.value = await caseApi.detail(ticketId)
  }
}

function onExport(id) {
  window.open(caseApi.exportUrl(id), '_blank')
}

async function load() {
  loading.value = true
  try {
    rows.value = await caseApi.list()
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <el-input v-model="keyword" placeholder="按标题搜索" clearable style="width: 220px">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <div class="spacer" />
        <el-button @click="load" :loading="loading">刷新</el-button>
      </div>

      <el-table :data="filtered" v-loading="loading" border stripe size="small" class="mt">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="ticketNo" label="故障单号" width="160" />
        <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
        <el-table-column prop="deviceType" label="设备类型" width="100" />
        <el-table-column prop="faultCategory" label="故障类别" width="100" />
        <el-table-column prop="summary" label="诊断结论" min-width="240" show-overflow-tooltip />
        <el-table-column label="首字耗时" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.firstTokenMs != null" size="small"
                    :type="row.firstTokenMs <= 5000 ? 'success' : 'warning'">
              {{ row.firstTokenMs }} ms
            </el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="statusMap[row.status]?.type">{{ statusMap[row.status]?.t || row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="提交时间" width="165" />
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">查看详情</el-button>
            <el-button link type="success" size="small" :disabled="row.adopted === 1" @click="onAdopt(row.id)">
              {{ row.adopted === 1 ? '已采纳' : '标记采纳' }}
            </el-button>
            <el-button link size="small" @click="onExport(row.id)">导出</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-drawer v-model="drawer" title="故障处理详情" size="62%" direction="rtl">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="故障单号">{{ detail.ticket.ticketNo }}</el-descriptions-item>
            <el-descriptions-item label="提交时间">{{ detail.ticket.createTime }}</el-descriptions-item>
            <el-descriptions-item label="标题">{{ detail.ticket.title }}</el-descriptions-item>
            <el-descriptions-item label="设备类型">{{ detail.ticket.deviceType }} / {{ detail.ticket.faultCategory }}</el-descriptions-item>
            <el-descriptions-item label="现象描述" :span="2">{{ detail.ticket.phenomenon }}</el-descriptions-item>
          </el-descriptions>

          <div class="toolbar mt">
            <el-button type="success" size="small" @click="onAdopt(detail.ticket.id)">标记建议已采纳</el-button>
            <el-button size="small" @click="onExport(detail.ticket.id)">导出处理单 (Word)</el-button>
          </div>

          <el-divider>日志原文</el-divider>
          <pre class="mono log">{{ detail.ticket.logText }}</pre>

          <el-divider>诊断记录（共 {{ detail.records.length }} 条）</el-divider>

          <el-card v-for="(r, i) in detail.records" :key="r.id" shadow="never" class="rec">
            <template #header>
              <div class="rec-head">
                <b>{{ r.answer?.followUp ? '追问' : '诊断轮次 ' + (i + 1) }}</b>
                <span class="muted">
                  首字 {{ r.firstTokenMs }} ms｜总计 {{ r.costMs }} ms｜{{ r.modelName }}
                  <el-tag v-if="r.adopted === 1" size="small" type="success">已采纳</el-tag>
                </span>
              </div>
            </template>

            <!-- 追问记录 -->
            <template v-if="r.answer?.followUp">
              <p><b>问题：</b>{{ r.keywords }}</p>
              <p class="answer">{{ r.answer.followUp }}</p>
            </template>

            <!-- 诊断记录 -->
            <template v-else>
              <el-alert type="success" :closable="false" :title="r.answer?.summary" />
              <div v-for="(c, j) in r.answer?.causes || []" :key="j" class="cause">
                <p class="cause-t">{{ j + 1 }}. {{ c.cause }}（可信度 {{ c.confidence }}）</p>
                <p class="muted">依据：{{ c.evidence }}</p>
                <ol class="step-list">
                  <li v-for="(s, k) in c.checkSteps || []" :key="k">{{ s }}</li>
                </ol>
                <p><b>处置建议：</b>{{ c.fix }}</p>
              </div>
            </template>

            <el-divider content-position="left">引用依据</el-divider>
            <div v-for="(h, k) in r.hits || []" :key="k" class="hit">
              <el-tag size="small">资料{{ k + 1 }}</el-tag>
              <b>{{ h.title }}</b>
              <el-tag size="small" type="success">相似度 {{ Number(h.score).toFixed(4) }}</el-tag>
              <div class="hit-content">{{ h.content }}</div>
            </div>
            <el-empty v-if="!r.hits?.length" :image-size="50" description="无命中资料" />
          </el-card>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.page {
  max-width: 1300px;
  margin: 0 auto;
}

.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
}

.spacer {
  flex: 1;
}

.mt {
  margin-top: 12px;
}

.log {
  background: #f5f7fa;
  padding: 10px;
  border-radius: 4px;
  max-height: 220px;
  overflow: auto;
  white-space: pre-wrap;
  margin: 0;
}

.rec {
  margin-bottom: 12px;
}

.rec-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.muted {
  color: #909399;
  font-size: 12px;
}

.answer {
  line-height: 1.9;
  white-space: pre-wrap;
}

.cause {
  margin-top: 10px;
  padding-left: 8px;
  border-left: 3px solid #e4e7ed;
}

.cause-t {
  font-weight: 600;
  margin: 0 0 4px;
}

.hit {
  padding: 6px 0;
  border-bottom: 1px dashed #e4e7ed;
}

.hit:last-child {
  border-bottom: none;
}
</style>
