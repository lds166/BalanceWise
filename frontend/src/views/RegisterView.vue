<template>
  <div class="auth-wrap">
    <el-card class="auth-card">
      <div class="brand">
        <div class="mark">账</div>
        <h2>创建账号</h2>
        <p>注册即为您初始化常用收支分类，开箱即用</p>
      </div>
      <el-form :model="form" @submit.prevent="submit">
        <el-form-item>
          <el-input v-model="form.username" placeholder="用户名（4-32 位字母、数字或下划线）" size="large" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.nickname" placeholder="昵称（选填）" size="large" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" placeholder="密码（至少 6 位）" size="large" show-password />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.confirmPassword" type="password" placeholder="确认密码" size="large" show-password />
        </el-form-item>
        <el-button type="primary" size="large" style="width: 100%" :loading="loading" native-type="submit">注 册</el-button>
      </el-form>
      <div class="links">已有账号？<router-link to="/login">直接登录</router-link></div>
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
const form = reactive({ username: '', nickname: '', password: '', confirmPassword: '' })

async function submit() {
  if (form.password !== form.confirmPassword) {
    ElMessage.error('两次输入的密码不一致')
    return
  }
  loading.value = true
  try {
    await store.register(form)
    ElMessage.success('注册成功，请登录')
    router.push('/login')
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
