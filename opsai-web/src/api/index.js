import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

/**
 * 所有后端请求的统一出口。
 * - 自动带上登录 token（X-Token 请求头）
 * - 统一处理 401（跳回登录页）和其他错误（弹提示）
 */
const api = axios.create({
  baseURL: '/api',
  timeout: 300000 // 本地大模型生成慢，超时给足 5 分钟
})

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('opsai_token')
  if (token) config.headers['X-Token'] = token
  return config
})

api.interceptors.response.use(
  (resp) => resp.data,
  (err) => {
    const status = err.response?.status
    const msg = err.response?.data?.message || err.message || '请求失败'
    if (status === 401) {
      localStorage.removeItem('opsai_token')
      localStorage.removeItem('opsai_user')
      ElMessage.error('登录已过期，请重新登录')
      router.push('/login')
    } else {
      ElMessage.error(msg)
    }
    return Promise.reject(err)
  }
)

export default api

/* ---------- 具体接口 ---------- */

export const authApi = {
  login: (username, password) => api.post('/auth/login', { username, password }),
  me: () => api.get('/auth/me'),
  logout: () => api.post('/auth/logout')
}

export const kbApi = {
  list: () => api.get('/kb/documents'),
  add: (doc) => api.post('/kb/document', doc),
  audit: (id, status) => api.post(`/kb/document/${id}/audit?status=${status}`),
  reindex: (id) => api.post(`/kb/document/${id}/reindex`),
  remove: (id) => api.delete(`/kb/document/${id}`)
}

export const diagApi = {
  health: () => api.get('/health'),
  diagnose: (form) => api.post('/diagnose', form),
  followUp: (ticketId, question) => api.post('/diagnose/followup', { ticketId, question })
}

export const caseApi = {
  list: () => api.get('/tickets'),
  detail: (id) => api.get(`/tickets/${id}`),
  adopt: (id, adopted) => api.post(`/tickets/${id}/adopt?adopted=${adopted}`),
  exportUrl: (id) => `/api/tickets/${id}/export`
}

export const statsApi = {
  overview: () => api.get('/stats/overview')
}

/* ---------- 只读用户（普通用户）的极简问答 ---------- */
export const assistApi = {
  /** 提问：question 是用户打的字，imageText 是截图 OCR 出来的文字 */
  ask: (payload) => api.post('/assist/ask', payload),

  /** 上传截图，返回 { path } */
  uploadImage: (file) => {
    const fd = new FormData()
    fd.append('file', file)
    return api.post('/upload/image', fd, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  }
}

/* ---------- 运维管理：操作日志 ---------- */
export const logApi = {
  list: (params) => api.get('/logs', { params })
}

/* ---------- 运维管理：自愈 ---------- */
export const selfHealApi = {
  /** 健康指标概览 */
  metrics: () => api.get('/selfheal/metrics'),
  /** 事件列表 */
  events: () => api.get('/selfheal/events'),
  /** 手动巡检（只采集指标、建事件，不动任何东西） */
  scan: () => api.post('/selfheal/scan'),
  /** AI 分析原因（慢，约 20~40 秒） */
  diagnose: (id) => api.post(`/selfheal/events/${id}/diagnose`),
  /** 重新生成 dry-run 预览 */
  propose: (id) => api.post(`/selfheal/events/${id}/propose`),
  /** 执行处置动作（仅管理员） */
  execute: (id) => api.post(`/selfheal/events/${id}/execute`),
  /** 忽略事件 */
  ignore: (id) => api.post(`/selfheal/events/${id}/ignore`),
  /** 系统具备的白名单动作 */
  actions: () => api.get('/selfheal/actions')
}
