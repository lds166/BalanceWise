<template>
  <el-container style="height: 100vh">
    <el-aside width="216px" style="background: #1e2235">
      <div class="logo"><span class="mark">账</span> BalanceWise</div>
      <el-menu :default-active="$route.path" router background-color="#1e2235"
               text-color="#9aa1b9" active-text-color="#ffffff">
        <el-menu-item index="/dashboard"><el-icon><HomeFilled /></el-icon>首页仪表盘</el-menu-item>
        <el-menu-item index="/record"><el-icon><EditPen /></el-icon>记一笔</el-menu-item>
        <el-menu-item index="/transactions"><el-icon><Notebook /></el-icon>流水</el-menu-item>
        <el-menu-item index="/import"><el-icon><Download /></el-icon>账单导入</el-menu-item>
        <el-menu-item index="/accounts"><el-icon><CreditCard /></el-icon>账户管理</el-menu-item>
        <el-menu-item index="/categories"><el-icon><CollectionTag /></el-icon>分类管理</el-menu-item>
        <el-menu-item index="/stats"><el-icon><DataAnalysis /></el-icon>统计报表</el-menu-item>
        <el-menu-item index="/user"><el-icon><User /></el-icon>用户中心</el-menu-item>
      </el-menu>
      <div class="logout" @click="doLogout">↩ 退出登录</div>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span class="title">{{ $route.meta.title }}</span>
        <span class="nick">{{ store.user?.nickname }}</span>
      </el-header>
      <el-main style="background: #f5f6fa">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import {
  HomeFilled, EditPen, Notebook, Download, CreditCard, CollectionTag, DataAnalysis, User,
} from '@element-plus/icons-vue'

const router = useRouter()
const store = useUserStore()

function doLogout() {
  store.logout()
  router.push('/login')
}
</script>

<style scoped>
.logo { color: #fff; font-size: 17px; font-weight: 700; padding: 20px 18px; }
.logo .mark {
  display: inline-block; width: 26px; height: 26px; border-radius: 8px; line-height: 26px;
  text-align: center; font-size: 14px;
  background: linear-gradient(135deg, #4e63f2, #7b5cf0); margin-right: 6px;
}
.logout {
  color: #9aa1b9; padding: 14px 24px; cursor: pointer; font-size: 14px;
  position: absolute; bottom: 0; width: 216px; box-sizing: border-box;
}
.logout:hover { color: #fff; background: rgba(255, 255, 255, .06); }
.header {
  display: flex; justify-content: space-between; align-items: center;
  background: #fff; border-bottom: 1px solid #e6e8f0;
}
.header .title { font-size: 16px; font-weight: 600; }
.header .nick { color: #8a90a3; font-size: 14px; }
</style>
