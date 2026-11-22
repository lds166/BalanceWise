<template>
  <div class="auth-wrap">
    <div class="auth-card">
      <div class="brand">
        <div class="logo">🌱</div>
        <h2>创建账号</h2>
        <p>注册即为您备好常用收支分类，开箱就能记</p>
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
        <el-button class="submit" type="primary" size="large" :loading="loading" native-type="submit">
          注 册
        </el-button>
      </el-form>
      <div class="links">已有账号？<router-link to="/login">直接登录</router-link></div>
    </div>
    <div class="foot-emoji" aria-hidden="true">💰 🎉 📈 🧧 💼</div>
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
  min-height: 100vh;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  background: var(--bw-bg);
  position: relative;
  overflow: hidden;
}
.auth-wrap::before, .auth-wrap::after {
  content: ''; position: absolute; border-radius: 50%; filter: blur(70px); opacity: 0.5;
}
.auth-wrap::before { width: 340px; height: 340px; background: var(--bw-primary-soft); top: -80px; right: -80px; }
.auth-wrap::after { width: 280px; height: 280px; background: var(--bw-income-soft); bottom: -60px; left: -60px; }


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
  background: var(--bw-income-soft); border-radius: 22px;
}
.brand h2 { font-size: 22px; margin: 0; color: var(--bw-text); }
.brand p { color: var(--bw-text-secondary); font-size: 13px; margin-top: 8px; }
.submit { width: 100%; font-weight: 700; letter-spacing: 6px; border-radius: 999px; margin-top: 4px; }
.links { text-align: center; margin-top: 20px; color: var(--bw-text-secondary); font-size: 13px; }
.links a { color: var(--bw-primary); text-decoration: none; font-weight: 600; }
.foot-emoji { position: relative; z-index: 1; margin-top: 26px; font-size: 20px; letter-spacing: 10px; opacity: 0.75; }
</style>
