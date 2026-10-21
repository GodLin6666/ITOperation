<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authApi, diagApi } from '../api'

const route = useRoute()
const router = useRouter()

const form = ref({ username: 'admin', password: 'admin123' })
const loading = ref(false)
const llm = ref(null)
const ldapEnabled = ref(false)
const authSource = ref('')

const accounts = [
  { u: 'admin', p: 'admin123', desc: '管理员：全部权限' },
  { u: 'engineer', p: 'engineer123', desc: '工程师：诊断、录知识' },
  { u: 'viewer', p: 'viewer123', desc: '只读：只能查看' }
]

onMounted(async () => {
  // 支持从桌面快捷方式带参数自动登录： /login?u=viewer&p=viewer123
  // 只读用户在自己电脑上双击图标就直接进问答页，不用记密码。
  const qu = route.query.u
  const qp = route.query.p
  if (qu && qp) {
    // 先清掉可能残留的旧会话（比如之前用管理员登录过），
    // 否则旧 token 会干扰角色判断，出现"界面还是上一个角色"的问题。
    localStorage.removeItem('opsai_token')
    localStorage.removeItem('opsai_user')
    form.value.username = String(qu)
    form.value.password = String(qp)
    await onLogin()
    return
  }
  try {
    llm.value = await diagApi.health()
    ldapEnabled.value = !!llm.value?.ldapEnabled
  } catch {
    llm.value = null
  }
})

async function onLogin() {
  if (!form.value.username || !form.value.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    const data = await authApi.login(form.value.username, form.value.password)
    localStorage.setItem('opsai_token', data.token)
    localStorage.setItem('opsai_user', JSON.stringify(data))
    authSource.value = data.authSource || ''
    ElMessage.success('登录成功，欢迎 ' + data.realName + (data.authSource === 'LDAP' ? '（域账号）' : ''))
    // 只读用户进极简问答页，运维人员进管理台
    const fallback = data.role === 'viewer' ? '/assist' : '/diagnose'
    router.push(route.query.redirect || fallback)
  } catch {
    /* 错误提示已由拦截器统一处理 */
  } finally {
    loading.value = false
  }
}

function useAccount(a) {
  form.value.username = a.u
  form.value.password = a.p
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card">
      <div class="brand">
        <el-icon size="30" color="#409eff"><Monitor /></el-icon>
        <h2>IT 运维故障智能辅助排查工具</h2>
        <p>本地大模型 + 本地知识库，全程不联网</p>
      </div>

      <el-form label-position="top" @submit.prevent>
        <el-form-item :label="ldapEnabled ? '域账号 / 用户名' : '用户名'">
          <el-input v-model="form.username" :placeholder="ldapEnabled ? '域账号（如 zhangsan）' : '请输入用户名'" size="large" @keyup.enter="onLogin">
            <template #prefix><el-icon><User /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password placeholder="请输入密码"
                    size="large" @keyup.enter="onLogin">
            <template #prefix><el-icon><Lock /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-button type="primary" size="large" style="width: 100%" :loading="loading" @click="onLogin">
          {{ loading ? '登录中…' : '登 录' }}
        </el-button>
      </el-form>

      <el-alert v-if="ldapEnabled" class="llm" type="info" :closable="false" show-icon
                title="企业域环境：直接用域账号登录即可，密码由域控校验，本系统不保存" />

      <el-divider>{{ ldapEnabled ? '本地应急账号（点击填充）' : '演示账号（点击填充）' }}</el-divider>
      <div class="accounts">
        <el-tag v-for="a in accounts" :key="a.u" class="acc" @click="useAccount(a)">
          {{ a.u }} / {{ a.p }} · {{ a.desc }}
        </el-tag>
      </div>

      <el-alert v-if="llm" class="llm" type="success" :closable="false" show-icon
                :title="`本地模型已就绪：${llm.llm?.models?.join('、') || ''}`" />
      <el-alert v-else class="llm" type="warning" :closable="false" show-icon
                title="暂时连不上后端或 Ollama，请确认两者都在运行" />
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1f2d3d 0%, #2b5876 60%, #4e4376 100%);
  padding: 16px;
}

.login-card {
  width: 440px;
  max-width: 100%;
  border-radius: 10px;
}

.brand {
  text-align: center;
  margin-bottom: 18px;
}

.brand h2 {
  margin: 8px 0 4px;
  font-size: 19px;
}

.brand p {
  margin: 0;
  color: #909399;
  font-size: 12px;
}

.accounts {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.acc {
  cursor: pointer;
  width: 100%;
  justify-content: flex-start;
}

.llm {
  margin-top: 14px;
}
</style>
