<template>
  <div class="bw-page">
    <div class="bw-page-title">个人中心</div>
    <el-row :gutter="16">
      <el-col :span="10">
        <el-card shadow="hover">
          <template #header><span>个人资料</span></template>
          <el-form label-width="80px" style="max-width: 360px">
            <el-form-item label="用户名">
              <span>{{ store.user?.username }}</span>
            </el-form-item>
            <el-form-item label="昵称">
              <el-input v-model="nickForm.nickname" maxlength="32" placeholder="选填，默认同用户名" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="nickForm.saving" @click="saveNickname">保存昵称</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>
      <el-col :span="14">
        <el-card shadow="hover">
          <template #header><span>修改密码</span></template>
          <el-form label-width="90px" style="max-width: 420px">
            <el-form-item label="原密码">
              <el-input v-model="pwdForm.oldPassword" type="password" show-password />
            </el-form-item>
            <el-form-item label="新密码">
              <el-input v-model="pwdForm.newPassword" type="password" show-password placeholder="6-64 位" />
            </el-form-item>
            <el-form-item label="确认新密码">
              <el-input v-model="pwdForm.confirm" type="password" show-password />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="pwdForm.saving" @click="changePassword">修改密码</el-button>
              <span class="hint" style="margin-left: 12px">修改成功后需重新登录</span>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="hover" style="margin-top: 16px">
      <template #header>
        <div class="card-head">
          <span>🤖 AI 智能分类</span>
          <el-switch v-model="aiForm.enabled" active-text="启用" inline-prompt />
        </div>
      </template>
      <el-form label-width="90px" style="max-width: 520px">
        <el-form-item label="接口地址">
          <el-input v-model="aiForm.baseUrl"
                    placeholder="OpenAI 兼容地址，如 https://api.siliconflow.cn/v1" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="aiForm.apiKey" type="password" show-password
                    :placeholder="aiForm.hasKey ? `已保存（${aiForm.keyMasked}），留空不修改` : '必填，自备 OpenAI 兼容 Key'" />
        </el-form-item>
        <el-form-item label="模型">
          <el-input v-model="aiForm.model" placeholder="如 Qwen/Qwen2.5-7B-Instruct" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="aiForm.saving" @click="saveAi">保存</el-button>
          <el-button :loading="aiForm.testing" @click="testAi">测试连接</el-button>
          <span class="hint" style="margin-left: 12px">启用后，导入预览时规则未命中的流水交给大模型自动分类</span>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="hover" style="margin-top: 16px">
      <template #header><span>🎨 外观主题</span></template>
      <div class="theme-grid">
        <button
          v-for="t in theme.themes"
          :key="t.id"
          class="theme-option"
          :class="{ active: theme.current === t.id }"
          @click="theme.setTheme(t.id)"
        >
          <span class="swatch" :style="{ background: t.dot }" />
          <span class="theme-meta">
            <span class="theme-name">{{ t.name }}</span>
            <span class="theme-desc">{{ t.desc }}</span>
          </span>
          <span v-if="theme.current === t.id" class="theme-check">✓ 使用中</span>
        </button>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api/request'
import { useUserStore } from '../stores/user'
import { useThemeStore } from '../stores/theme'

const router = useRouter()
const store = useUserStore()
const theme = useThemeStore()

const nickForm = reactive({ nickname: store.user?.nickname || '', saving: false })
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirm: '', saving: false })

// AI 智能分类配置（用户级）：Key 只存不回显，回显仅有掩码；留空表示不修改已存 Key
const aiForm = reactive({
  enabled: false, baseUrl: '', model: '', apiKey: '',
  hasKey: false, keyMasked: '', saving: false, testing: false,
})

onMounted(async () => {
  const res = await request.get('/user/ai-config')
  aiForm.enabled = res.data.enabled
  aiForm.baseUrl = res.data.baseUrl || ''
  aiForm.model = res.data.model || ''
  aiForm.hasKey = res.data.hasKey
  aiForm.keyMasked = res.data.keyMasked
})

async function saveAi() {
  if (!aiForm.baseUrl || !aiForm.baseUrl.trim()) {
    return ElMessage.warning('接口地址不能为空')
  }
  if (!aiForm.hasKey && (!aiForm.apiKey || !aiForm.apiKey.trim())) {
    return ElMessage.warning('首次启用请填写 API Key')
  }
  aiForm.saving = true
  try {
    const res = await request.put('/user/ai-config', {
      enabled: aiForm.enabled,
      baseUrl: aiForm.baseUrl.trim(),
      model: aiForm.model,
      apiKey: aiForm.apiKey, // 留空 = 不修改已存 Key
    })
    aiForm.hasKey = res.data.hasKey
    aiForm.keyMasked = res.data.keyMasked
    aiForm.apiKey = ''
    ElMessage.success(aiForm.enabled ? 'AI 配置已保存并启用' : 'AI 配置已保存（未启用）')
  } finally {
    aiForm.saving = false
  }
}

async function testAi() {
  if (!aiForm.baseUrl || !aiForm.baseUrl.trim()) {
    return ElMessage.warning('请先填写接口地址')
  }
  aiForm.testing = true
  try {
    const res = await request.post('/user/ai-config/test', {
      enabled: aiForm.enabled,
      baseUrl: aiForm.baseUrl.trim(),
      model: aiForm.model,
      apiKey: aiForm.apiKey, // 留空回退已存 Key
    })
    if (res.data === 'ok') {
      ElMessage.success('连接成功，接口可用')
    } else {
      ElMessage.error('连接失败：' + res.data)
    }
  } finally {
    aiForm.testing = false
  }
}

async function saveNickname() {
  if (!nickForm.nickname || !nickForm.nickname.trim()) {
    ElMessage.warning('昵称不能为空')
    return
  }
  nickForm.saving = true
  try {
    await request.put('/user/nickname', { nickname: nickForm.nickname.trim() })
    // 同步 Pinia 与 localStorage，MainLayout 头部昵称即时刷新
    store.user = { ...store.user, nickname: nickForm.nickname.trim() }
    localStorage.setItem('bw_user', JSON.stringify(store.user))
    ElMessage.success('昵称已更新')
  } finally {
    nickForm.saving = false
  }
}

async function changePassword() {
  if (!pwdForm.oldPassword || !pwdForm.newPassword) {
    ElMessage.warning('请填写原密码与新密码')
    return
  }
  if (pwdForm.newPassword.length < 6 || pwdForm.newPassword.length > 64) {
    ElMessage.warning('新密码长度须为 6-64 位')
    return
  }
  if (pwdForm.newPassword !== pwdForm.confirm) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  try {
    await ElMessageBox.confirm('修改密码后需退出并重新登录，确定修改吗？', '修改确认', { type: 'warning' })
  } catch { return }
  pwdForm.saving = true
  try {
    await request.put('/user/password', {
      oldPassword: pwdForm.oldPassword,
      newPassword: pwdForm.newPassword,
    })
    ElMessage.success('密码已修改，请重新登录')
    store.logout()
    router.push('/login')
  } finally {
    pwdForm.saving = false
  }
}
</script>

<style scoped>
.hint { font-size: 12px; color: var(--bw-text-secondary); }
.card-head { display: flex; justify-content: space-between; align-items: center; }

.theme-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 12px; }
.theme-option {
  display: flex; align-items: center; gap: 12px; text-align: left;
  padding: 14px; border-radius: 14px; cursor: pointer;
  border: 2px solid var(--bw-border); background: var(--bw-bg);
  font-family: inherit; transition: all 0.15s ease;
}
.theme-option:hover { border-color: var(--bw-primary); transform: translateY(-1px); }
.theme-option.active { border-color: var(--bw-primary); background: var(--bw-primary-soft); }
.swatch { width: 30px; height: 30px; border-radius: 10px; flex: none; box-shadow: inset 0 0 0 2px rgba(255,255,255,0.35); }
.theme-meta { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.theme-name { font-weight: 700; font-size: 14px; color: var(--bw-text); }
.theme-desc { font-size: 12px; color: var(--bw-text-secondary); }
.theme-check { margin-left: auto; color: var(--bw-primary); font-size: 12px; font-weight: 600; white-space: nowrap; }
</style>
