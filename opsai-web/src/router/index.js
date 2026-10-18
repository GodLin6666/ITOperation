import { createRouter, createWebHistory } from 'vue-router'

/**
 * 角色分流：
 *   只读用户（viewer）  → 只有一个极简问答页 /assist
 *   管理员 / 运维工程师 → 诊断 + 知识库 + 案例 + 统计 + 操作日志
 */
const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/Login.vue'),
    meta: { public: true, title: '登录' }
  },
  {
    path: '/assist',
    name: 'assist',
    component: () => import('../views/Assist.vue'),
    meta: { title: '问题助手' }
  },
  {
    path: '/diagnose',
    name: 'diagnose',
    component: () => import('../views/Diagnose.vue'),
    meta: { title: '故障智能诊断', icon: 'FirstAidKit', roles: ['admin', 'engineer'] }
  },
  {
    path: '/knowledge',
    name: 'knowledge',
    component: () => import('../views/Knowledge.vue'),
    meta: { title: '知识库管理', icon: 'Collection', roles: ['admin', 'engineer'] }
  },
  {
    path: '/cases',
    name: 'cases',
    component: () => import('../views/Cases.vue'),
    meta: { title: '案例与处理单', icon: 'Document', roles: ['admin', 'engineer'] }
  },
  {
    path: '/stats',
    name: 'stats',
    component: () => import('../views/Stats.vue'),
    meta: { title: '统计分析', icon: 'TrendCharts', roles: ['admin', 'engineer'] }
  },
  {
    path: '/logs',
    name: 'logs',
    component: () => import('../views/Logs.vue'),
    meta: { title: '操作日志', icon: 'Tickets', roles: ['admin', 'engineer'] }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

function currentUser() {
  try {
    return JSON.parse(localStorage.getItem('opsai_user') || 'null')
  } catch {
    return null
  }
}

/** 按角色决定登录后落到哪一页 */
function landing() {
  return currentUser()?.role === 'viewer' ? '/assist' : '/diagnose'
}

router.beforeEach((to) => {
  const token = localStorage.getItem('opsai_token')

  // 未登录 → 去登录页
  if (!to.meta?.public && !token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  // 已登录还去登录页 → 直接进主页
  if (to.path === '/login') {
    // 但带 u/p 的免登录链接例外：即使当前已登录（可能是别的账号），
    // 也要允许进登录页，让它用新账号重新登录并覆盖旧会话。
    const isAutoLogin = !!(to.query.u && to.query.p)
    if (token && !isAutoLogin) {
      return { path: landing() }
    }
    return true
  }
  // 根路径按角色分流
  if (to.path === '/') {
    return { path: landing() }
  }
  // 越权访问 → 弹回自己的主页
  const roles = to.meta?.roles
  if (roles) {
    const u = currentUser()
    if (!u || !roles.includes(u.role)) {
      return { path: landing() }
    }
  }
  return true
})

export default router
