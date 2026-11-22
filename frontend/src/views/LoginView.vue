<template>
  <div class="auth-wrap">
    <div class="auth-card">
      <div class="brand">
        <div class="logo">🌿</div>
        <h2>欢迎回来</h2>
        <p>记好每一笔钱，过更明白的生活</p>
      </div>
      <el-form :model="form" @submit.prevent="submit">
        <el-form-item>
          <el-input v-model="form.username" placeholder="用户名" size="large" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" placeholder="密码" size="large" show-password />
        </el-form-item>
        <el-button class="submit" type="primary" size="large" :loading="loading" native-type="submit">
          登 录
        </el-button>
      </el-form>
      <div class="links">还没有账号？<router-link to="/register">立即注册</router-link></div>
    </div>
    <div class="foot-emoji" aria-hidden="true">🍜 🚌 🛍️ 🏠 🎮 📚 💊</div>
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
  min-height: 100vh;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  background: var(--bw-bg);
  position: relative;
  overflow: hidden;
}
.auth-wrap::before, .auth-wrap::after {
  content: ''; position: absolute; border-radius: 50%; filter: blur(70px); opacity: 0.5;
}
.auth-wrap::before { width: 340px; height: 340px; background: var(--bw-primary-soft); top: -80px; left: -80px; }
.auth-wrap::after { width: 280px; height: 280px; background: var(--bw-income-soft); bottom: -60px; right: -60px; }


.auth-card {
  position: relative; z-index: 1;
  width: 400px; max-width: calc(100vw - 40px);
  background: var(--bw-card);
  border-radius: var(--bw-radius-lg);
  box-shadow: var(--bw-shadow-hover);
  padding: 36px 32px 28px;
}
.brand { text-align: center; margin-bottom: 26px; }
.brand .logo {
  font-size: 40px; line-height: 1;
  width: 68px; height: 68px; margin: 0 auto 14px;
  display: flex; align-items: center; justify-content: center;
  background: var(--bw-primary-soft); border-radius: 22px;
}
.brand h2 { font-size: 22px; margin: 0; color: var(--bw-text); }
.brand p { color: var(--bw-text-secondary); font-size: 13px; margin-top: 8px; }
.submit { width: 100%; font-weight: 700; letter-spacing: 6px; border-radius: 999px; margin-top: 4px; }
.links { text-align: center; margin-top: 20px; color: var(--bw-text-secondary); font-size: 13px; }
.links a { color: var(--bw-primary); text-decoration: none; font-weight: 600; }
.foot-emoji { position: relative; z-index: 1; margin-top: 26px; font-size: 20px; letter-spacing: 10px; opacity: 0.75; }
</style>
