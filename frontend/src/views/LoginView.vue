<template>
  <div class="auth-wrap">
    <el-card class="auth-card">
      <div class="brand">
        <div class="mark">账</div>
        <h2>BalanceWise 个人账务管理</h2>
        <p>多账户统一管理 · 账单导入自动记账 · 财务统计可视化</p>
      </div>
      <el-form :model="form" @submit.prevent="submit">
        <el-form-item>
          <el-input v-model="form.username" placeholder="用户名" size="large" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" placeholder="密码" size="large" show-password />
        </el-form-item>
        <el-button type="primary" size="large" style="width: 100%" :loading="loading" native-type="submit">登 录</el-button>
      </el-form>
      <div class="links">还没有账号？<router-link to="/register">立即注册</router-link></div>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../stores/user'

const router = useRouter()
const store = useUserStore()
const loading = ref(false)
const form = reactive({ username: '', password: '' })

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await store.login(form)
    ElMessage.success('登录成功')
    router.push('/dashboard')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.auth-wrap {
  min-height: 100vh; display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #20264a 0%, #3b4fd1 100%);
}
.auth-card { width: 400px; border-radius: 16px; }
.brand { text-align: center; margin-bottom: 24px; }
.brand .mark {
  width: 52px; height: 52px; border-radius: 14px; margin: 0 auto 12px; color: #fff;
  font-size: 26px; display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #4e63f2, #7b5cf0);
}
.brand h2 { font-size: 20px; }
.brand p { color: #8a90a3; font-size: 13px; margin-top: 6px; }
.links { text-align: center; margin-top: 18px; color: #8a90a3; font-size: 13px; }
</style>
