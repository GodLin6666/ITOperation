<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { kbApi } from '../api'

const loading = ref(false)
const docs = ref([])
const keyword = ref('')
const filterDevice = ref('')
const filterStatus = ref('')

const user = JSON.parse(localStorage.getItem('opsai_user') || 'null')
const canEdit = computed(() => user?.role === 'admin' || user?.role === 'engineer')
const isAdmin = computed(() => user?.role === 'admin')

const deviceTypes = [
  { label: '服务器', value: 'server' },
  { label: '数据库', value: 'db' },
  { label: '网络', value: 'network' },
  { label: '中间件', value: 'middleware' },
  { label: '应用', value: 'app' }
]
const categories = ['cpu', 'mem', 'disk', 'network', 'db', 'middleware', 'system', 'app']
const statusMap = { 0: { t: '待审核', type: 'info' }, 1: { t: '已发布', type: 'success' }, 2: { t: '已驳回', type: 'danger' } }

const filtered = computed(() =>
  docs.value.filter((d) => {
    if (filterDevice.value && d.deviceType !== filterDevice.value) return false
    if (filterStatus.value !== '' && String(d.status) !== String(filterStatus.value)) return false
    if (keyword.value && !(d.title || '').includes(keyword.value)) return false
    return true
  })
)

async function load() {
  loading.value = true
  try {
    docs.value = await kbApi.list()
  } finally {
    loading.value = false
  }
}

/* ---------- 新增 ---------- */
const dialog = ref(false)
const saving = ref(false)
const form = ref({ title: '', deviceType: 'server', faultCategory: 'disk', docType: 'manual', content: '', source: '自建' })

function openAdd() {
  form.value = { title: '', deviceType: 'server', faultCategory: 'disk', docType: 'manual', content: '', source: '自建' }
  dialog.value = true
}

async function onSave() {
  if (!form.value.title?.trim()) return ElMessage.warning('请填写标题')
  if ((form.value.content || '').trim().length < 30) return ElMessage.warning('正文太短，建议 300 字以上，否则切块后检索效果差')
  saving.value = true
  try {
    const r = await kbApi.add(form.value)
    ElMessage.success(`录入成功，已切成 ${r.chunkCount} 块并算好向量`)
    dialog.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function onAudit(row, status) {
  await kbApi.audit(row.id, status)
  ElMessage.success(status === 1 ? '已发布，立刻可被检索到' : '已驳回，不会被检索')
  await load()
}

async function onReindex(row) {
  ElMessage.info('正在重新分块并计算向量…')
  const r = await kbApi.reindex(row.id)
  ElMessage.success(`重建完成，共 ${r.chunkCount} 块`)
  await load()
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(`确定删除「${row.title}」？该操作会同时删除它的所有分块向量。`, '警告', { type: 'warning' })
  } catch {
    return
  }
  await kbApi.remove(row.id)
  ElMessage.success('已删除')
  await load()
}

onMounted(load)
</script>

<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <el-input v-model="keyword" placeholder="按标题搜索" clearable style="width: 200px">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-select v-model="filterDevice" placeholder="设备类型" clearable style="width: 130px">
          <el-option v-for="d in deviceTypes" :key="d.value" :label="d.label" :value="d.value" />
        </el-select>
        <el-select v-model="filterStatus" placeholder="状态" clearable style="width: 120px">
          <el-option label="待审核" :value="0" />
          <el-option label="已发布" :value="1" />
          <el-option label="已驳回" :value="2" />
        </el-select>
        <div class="spacer" />
        <el-button type="primary" :disabled="!canEdit" @click="openAdd">
          <el-icon><Plus /></el-icon>&nbsp;新增知识
        </el-button>
        <el-button @click="load" :loading="loading">刷新</el-button>
      </div>

      <el-alert class="tip" type="info" :closable="false" show-icon
                title="只有「已发布」的知识才会被检索使用；正文会在保存时自动切成 400 字一块并计算 1024 维向量。" />

      <el-table :data="filtered" v-loading="loading" border stripe size="small" class="mt">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
        <el-table-column prop="deviceType" label="设备类型" width="100" />
        <el-table-column prop="faultCategory" label="故障类别" width="100" />
        <el-table-column prop="docType" label="类型" width="90" />
        <el-table-column prop="chunkCount" label="分块数" width="80" align="center" />
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="statusMap[row.status]?.type">{{ statusMap[row.status]?.t || row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="updateTime" label="更新时间" width="160" />
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status !== 1" link type="success" size="small" :disabled="!isAdmin" @click="onAudit(row, 1)">发布</el-button>
            <el-button v-if="row.status !== 2" link type="warning" size="small" :disabled="!isAdmin" @click="onAudit(row, 2)">驳回</el-button>
            <el-button link type="primary" size="small" :disabled="!isAdmin" @click="onReindex(row)">重建向量</el-button>
            <el-button link type="danger" size="small" :disabled="!isAdmin" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialog" title="新增知识条目" width="720px" top="6vh">
      <el-form label-position="top">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="标题">
              <el-input v-model="form.title" placeholder="例如：Nginx 502 排查手册" />
            </el-form-item>
          </el-col>
          <el-col :span="4">
            <el-form-item label="设备类型">
              <el-select v-model="form.deviceType" style="width: 100%">
                <el-option v-for="d in deviceTypes" :key="d.value" :label="d.label" :value="d.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="4">
            <el-form-item label="故障类别">
              <el-select v-model="form.faultCategory" style="width: 100%">
                <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="4">
            <el-form-item label="文档类型">
              <el-select v-model="form.docType" style="width: 100%">
                <el-option label="排查手册" value="manual" />
                <el-option label="历史案例" value="case" />
                <el-option label="处置方案" value="solution" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="正文（推荐结构：现象 / 影响 / 可能原因 / 排查步骤 / 处置方案 / 预防措施）">
          <el-input v-model="form.content" type="textarea" :rows="14"
                    placeholder="现象：……&#10;影响：……&#10;可能原因：1）…… 2）……&#10;排查步骤：1）命令 -> 看什么指标 -> 判断标准；2）……&#10;处置方案：临时处置…… 根本处置……&#10;预防措施：……" />
        </el-form-item>
        <el-form-item label="来源">
          <el-input v-model="form.source" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">
          {{ saving ? '正在分块并计算向量…' : '保存并向量化' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page {
  max-width: 1200px;
  margin: 0 auto;
}

.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}

.spacer {
  flex: 1;
}

.tip {
  margin-top: 12px;
}

.mt {
  margin-top: 12px;
}
</style>
