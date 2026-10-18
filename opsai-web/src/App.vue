<script setup>
import { ref, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { authApi } from './api'

const route = useRoute()
const router = useRouter()

const menus = [
  { path: '/diagnose', title: '故障智能诊断', icon: 'FirstAidKit' },
  { path: '/knowledge', title: '知识库管理', icon: 'Collection' },
  { path: '/cases', title: '案例与处理单', icon: 'Document' },
  { path: '/stats', title: '统计分析', icon: 'TrendCharts' },
  { path: '/selfheal', title: '自愈中心', icon: 'MagicStick' },
  { path: '/logs', title: '操作日志', icon: 'Tickets' }
]

const user = ref(JSON.parse(localStorage.getItem('opsai_user') || 'null'))
const isLoginPage = computed(() => route.path === '/login')
// 只读用户不套管理端外壳：Assist 页自带极简头部
const bareLayout = computed(() => isLoginPage.value || user.value?.role === 'viewer')
const activeMenu = computed(() => route.path)
const pageTitle = computed(() => route.meta?.title || '')

// 登录成功后 localStorage 会变，这里同步到界面右上角
watch(
  () => route.path,
  () => {
    user.value = JSON.parse(localStorage.getItem('opsai_user') || 'null')
  }
)

const roleText = computed(() => {
  const map = { admin: '管理员', engineer: '运维工程师', viewer: '只读用户' }
  return map[user.value?.role] || user.value?.role || ''
})

async function onLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await authApi.logout()
  } catch {
    /* 退出失败不影响本地清理 */
  }
  localStorage.removeItem('opsai_token')
  localStorage.removeItem('opsai_user')
  user.value = null
  ElMessage.success('已退出登录')
  router.push('/login')
}
</script>

<template>
  <!-- 登录页、以及只读用户的极简页：都不套管理端外壳 -->
  <router-view v-if="bareLayout" />

  <el-container v-else class="layout">
    <el-aside width="210px" class="aside hide-on-mobile">
      <div class="logo">
        <el-icon size="22"><Monitor /></el-icon>
        <span>运维排障助手</span>
      </div>
      <el-menu :default-active="activeMenu" router class="menu">
        <el-menu-item v-for="m in menus" :key="m.path" :index="m.path">
          <el-icon><component :is="m.icon" /></el-icon>
          <span>{{ m.title }}</span>
        </el-menu-item>
      </el-menu>
      <div class="aside-foot">本地部署 · 不联网</div>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-left">
          <el-dropdown class="hide-on-mobile" trigger="click" @command="(p) => router.push(p)">
            <el-button text><el-icon><Menu /></el-icon></el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-for="m in menus" :key="m.path" :command="m.path">
                  {{ m.title }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <span class="title">{{ pageTitle }}</span>
        </div>

        <el-dropdown>
          <span class="user">
            <el-icon><UserFilled /></el-icon>
            {{ user?.realName || user?.username || '未登录' }}
            <el-tag size="small" type="info" class="role-tag">{{ roleText }}</el-tag>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item disabled>{{ user?.username }}</el-dropdown-item>
              <el-dropdown-item divided @click="onLogout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>

      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100vh;
}

.aside {
  background: #1f2d3d;
  color: #fff;
  display: flex;
  flex-direction: column;
}

.logo {
  height: 56px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 16px;
  font-size: 16px;
  font-weight: 600;
  color: #fff;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.menu {
  flex: 1;
  border-right: none;
  background: transparent;
}

.menu :deep(.el-menu-item) {
  color: #c0c4cc;
}

.menu :deep(.el-menu-item:hover) {
  background: rgba(255, 255, 255, 0.06);
  color: #fff;
}

.menu :deep(.el-menu-item.is-active) {
  background: #409eff;
  color: #fff;
}

.aside-foot {
  padding: 12px 16px;
  font-size: 12px;
  color: #8492a6;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}

.header {
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 56px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 4px;
}

.title {
  font-size: 16px;
  font-weight: 600;
}

.user {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  outline: none;
  font-size: 14px;
}

.role-tag {
  margin-left: 2px;
}

.main {
  background: #f5f7fa;
  padding: 16px;
  overflow: auto;
}
</style>
