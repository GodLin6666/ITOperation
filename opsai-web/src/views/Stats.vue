<script setup>
import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue'
import * as echarts from 'echarts'
import { statsApi } from '../api'

const loading = ref(false)
const data = ref(null)
const charts = {}

const categoryEl = ref(null)
const deviceEl = ref(null)
const dailyEl = ref(null)
const latencyEl = ref(null)
const els = { category: categoryEl, device: deviceEl, daily: dailyEl, latency: latencyEl }

function make(key, option) {
  const dom = els[key].value
  if (!dom) return
  if (!charts[key]) charts[key] = echarts.init(dom)
  charts[key].setOption(option, true)
}

function render() {
  const d = data.value
  if (!d) return

  make('category', {
    tooltip: { trigger: 'item' },
    legend: { bottom: 0, type: 'scroll' },
    series: [
      {
        type: 'pie',
        radius: ['42%', '68%'],
        center: ['50%', '46%'],
        avoidLabelOverlap: true,
        itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
        label: { formatter: '{b}\n{c} 单' },
        data: Object.entries(d.categoryDist || {}).map(([k, v]) => ({ name: k, value: v }))
      }
    ]
  })

  make('device', {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 70, right: 20, top: 20, bottom: 30 },
    xAxis: { type: 'value', minInterval: 1 },
    yAxis: { type: 'category', data: Object.keys(d.deviceDist || {}) },
    series: [
      {
        type: 'bar',
        barWidth: 16,
        itemStyle: { color: '#409eff', borderRadius: [0, 4, 4, 0] },
        label: { show: true, position: 'right' },
        data: Object.values(d.deviceDist || {})
      }
    ]
  })

  const days = Object.keys(d.dailyTrend || {})
  make('daily', {
    tooltip: { trigger: 'axis' },
    grid: { left: 45, right: 20, top: 20, bottom: 30 },
    xAxis: { type: 'category', data: days, boundaryGap: false },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      {
        type: 'line',
        smooth: true,
        areaStyle: { opacity: 0.18 },
        itemStyle: { color: '#67c23a' },
        data: days.map((k) => d.dailyTrend[k])
      }
    ]
  })

  const lat = d.latency || []
  make('latency', {
    tooltip: { trigger: 'axis' },
    legend: { top: 0, data: ['首字耗时', '总耗时'] },
    grid: { left: 60, right: 20, top: 34, bottom: 30 },
    xAxis: { type: 'category', data: lat.map((x) => '#' + x.ticketId) },
    yAxis: { type: 'value', name: '毫秒' },
    series: [
      {
        name: '首字耗时',
        type: 'bar',
        itemStyle: { color: '#e6a23c' },
        data: lat.map((x) => x.firstTokenMs)
      },
      {
        name: '总耗时',
        type: 'bar',
        itemStyle: { color: '#409eff' },
        data: lat.map((x) => x.costMs)
      }
    ]
  })
}

function onResize() {
  Object.values(charts).forEach((c) => c.resize())
}

async function load() {
  loading.value = true
  try {
    data.value = await statsApi.overview()
    await nextTick()
    render()
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  load()
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  Object.values(charts).forEach((c) => c.dispose())
})
</script>

<template>
  <div class="page" v-loading="loading">
    <el-row :gutter="12">
      <el-col :xs="12" :sm="6">
        <el-card shadow="never" class="kpi">
          <div class="kpi-label">故障单总数</div>
          <div class="kpi-value">{{ data?.ticketCount ?? 0 }}</div>
          <div class="kpi-foot">诊断记录 {{ data?.recordCount ?? 0 }} 条</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="never" class="kpi">
          <div class="kpi-label">知识库</div>
          <div class="kpi-value">{{ data?.documentCount ?? 0 }}</div>
          <div class="kpi-foot">已发布 {{ data?.publishedCount ?? 0 }}｜向量块 {{ data?.chunkCount ?? 0 }}</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="never" class="kpi">
          <div class="kpi-label">平均首字耗时</div>
          <div class="kpi-value" :class="{ warn: (data?.avgFirstTokenMs ?? 0) > 5000 }">
            {{ data?.avgFirstTokenMs ?? 0 }} <span class="unit">ms</span>
          </div>
          <div class="kpi-foot">
            最大 {{ data?.maxFirstTokenMs ?? 0 }} ms｜5 秒内达标率 {{ data?.firstTokenUnder5sRate ?? 0 }}%
          </div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="never" class="kpi">
          <div class="kpi-label">建议采纳率</div>
          <div class="kpi-value">{{ data?.adoptRate ?? 0 }}<span class="unit">%</span></div>
          <div class="kpi-foot">已采纳 {{ data?.adoptedCount ?? 0 }} 条｜平均总耗时 {{ data?.avgCostMs ?? 0 }} ms</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="12" class="mt">
      <el-col :xs="24" :md="12">
        <el-card shadow="never">
          <template #header>故障类别分布</template>
          <div ref="categoryEl" class="chart"></div>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="12">
        <el-card shadow="never">
          <template #header>设备类型分布</template>
          <div ref="deviceEl" class="chart"></div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="12" class="mt">
      <el-col :xs="24" :md="12">
        <el-card shadow="never">
          <template #header>每日诊断量</template>
          <div ref="dailyEl" class="chart"></div>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="12">
        <el-card shadow="never">
          <template #header>最近 10 次诊断耗时（性能指标）</template>
          <div ref="latencyEl" class="chart"></div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="mt">
      <template #header>性能验收说明</template>
      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="首字返回要求">≤ 5000 ms</el-descriptions-item>
        <el-descriptions-item label="实测平均首字">{{ data?.avgFirstTokenMs ?? 0 }} ms</el-descriptions-item>
        <el-descriptions-item label="5 秒内达标率">{{ data?.firstTokenUnder5sRate ?? 0 }}%</el-descriptions-item>
      </el-descriptions>
      <p class="muted">
        数据来源：<code>diagnose_record</code> 表的 first_token_ms / cost_ms 字段，每次诊断自动留痕。
      </p>
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

.chart {
  height: 280px;
  width: 100%;
}

.kpi {
  text-align: center;
}

.kpi-label {
  color: #909399;
  font-size: 13px;
}

.kpi-value {
  font-size: 30px;
  font-weight: 700;
  color: #303133;
  margin: 6px 0;
}

.kpi-value.warn {
  color: #e6a23c;
}

.unit {
  font-size: 14px;
  font-weight: 400;
  color: #909399;
}

.kpi-foot {
  font-size: 12px;
  color: #909399;
}

.muted {
  color: #909399;
  font-size: 12px;
  margin-top: 10px;
}
</style>
