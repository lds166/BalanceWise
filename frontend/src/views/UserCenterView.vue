<template>
  <div>
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
  </div>
</template>

<script setup>
import { reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '../api/request'
import { useUserStore } from '../stores/user'

const router = useRouter()
const store = useUserStore()

const nickForm = reactive({ nickname: store.user?.nickname || '', saving: false })
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirm: '', saving: false })

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
.hint { font-size: 12px; color: #8a90a3; }
</style>
