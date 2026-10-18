<script setup>
/**
 * 操作日志（运维管理端）。
 *
 * 用户反馈「助手答得不对」时，管理员可以按用户 / 动作 / 关键词翻日志，
 * 看到当时问了什么、命中了几篇资料、用了多久，从而定位问题出在哪一环：
 *   检索没命中 → 知识库缺内容；命中了但答偏 → 提示词或模型问题。
 */
import { ref, computed, onMounted } from 'vue'
import { logApi } from '../api'

const loading = ref(false)
const items = ref([])
const byAction = ref({})
const total = ref(0)

const filterAction = ref('')
const filterUser = ref('')
const keyword = ref('')

const actionTypes = computed(() => Object.keys(byAction.value || {}))

const actionColor = (a) => {
  if (!a) return 'info'
  if (a.includes('失败')) return 'danger'
  if (a.includes('删除') || a.includes('驳回')) return 'warning'
  if (a.includes('询问') || a.includes('登录')) return 'primary'
  if (a.includes('发布') || a.includes('采纳')) return 'success'
  return 'info'
}

async function load() {
  loading.value = true
  try {
    const data = await logApi.list({
      limit: 300,
      action: filterAction.value || undefined,
      username: filterUser.value || undefined,
      keyword: keyword.value || undefined
    })
    items.value = data.items || []
    byAction.value = data.byAction || {}
    total.value = data.total || 0
  } finally {
    loading.value = false
  }
}

function reset() {
  filterAction.value = ''
  filterUser.value = ''
  keyword.value = ''
  load()
}

const fmtTime = (t) => (t ? String(t).replace('T', ' ').slice(0, 19) : '-')

onMounted(load)
</script>

<template>
  <div class="page">
    <!-- 概览 -->
    <el-card shadow="never" class="overview">
      <div class="ov-row">
        <div class="ov-item">
          <div class="ov-num">{{ total }}</div>
          <div class="ov-label">日志总数（最近 500 条内）</div>
        </div>
        <div class="ov-tags">
          <span class="tag-label">动作分布：</span>
          <el-tag
            v-for="(v, k) in byAction"
            :key="k"
            :type="actionColor(k)"
            size="small"
            class="dist-tag"
            :effect="filterAction === k ? 'dark' : 'light'"
            @click="filterAction = (filterAction === k ? '' : k); load()"
          >
            {{ k }} {{ v }}
          </el-tag>
          <span v-if="!Object.keys(byAction).length" class="muted">暂无日志</span>
        </div>
      </div>
    </el-card>

    <!-- 筛选 + 列表 -->
    <el-card shadow="never" class="mt">
      <div class="toolbar">
        <el-select v-model="filterAction" placeholder="全部动作" clearable style="width: 150px" @change="load">
          <el-option v-for="a in actionTypes" :key="a" :label="a" :value="a" />
        </el-select>
        <el-input v-model="filterUser" placeholder="用户名" clearable style="width: 130px" @keyup.enter="load" />
        <el-input v-model="keyword" placeholder="搜详情关键词（如某个故障单号、问题描述）" clearable style="width: 300px" @keyup.enter="load">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button type="primary" @click="load">查询</el-button>
        <el-button @click="reset">重置</el-button>
        <div class="spacer" />
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>

      <el-table :data="items" v-loading="loading" border stripe size="small" class="mt" max-height="620">
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column prop="username" label="用户" width="110" />
        <el-table-column label="动作" width="110">
          <template #default="{ row }">
            <el-tag :type="actionColor(row.action)" size="small" effect="plain">{{ row.action }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="detail" label="详情" min-width="320" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="detail">{{ row.detail || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="90" align="right">
          <template #default="{ row }">
            <span :class="{ slow: row.costMs > 30000 }">{{ row.costMs != null ? row.costMs + ' ms' : '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="ip" label="来源 IP" width="130">
          <template #default="{ row }">{{ row.ip || '-' }}</template>
        </el-table-column>
      </el-table>

      <div class="foot-tip">
        显示最近 {{ items.length }} 条 · 日志保留在 <code>sys_log</code> 表，可按动作分布快速定位异常
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.page {
  max-width: 1300px;
  margin: 0 auto;
}
.mt {
  margin-top: 12px;
}
.overview :deep(.el-card__body) {
  padding: 16px 18px;
}
.ov-row {
  display: flex;
  align-items: center;
  gap: 28px;
  flex-wrap: wrap;
}
.ov-item {
  text-align: center;
  min-width: 110px;
}
.ov-num {
  font-size: 26px;
  font-weight: 700;
  color: #303133;
}
.ov-label {
  font-size: 12px;
  color: #909399;
}
.ov-tags {
  flex: 1;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}
.tag-label {
  font-size: 12px;
  color: #909399;
}
.dist-tag {
  cursor: pointer;
}
.muted {
  color: #c0c4cc;
  font-size: 12px;
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
.detail {
  font-size: 13px;
  color: #606266;
}
.slow {
  color: #e6a23c;
  font-weight: 600;
}
.foot-tip {
  margin-top: 10px;
  font-size: 12px;
  color: #a8abb2;
}
</style>
