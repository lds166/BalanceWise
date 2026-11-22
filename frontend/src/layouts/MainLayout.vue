<template>
  <div class="shell">
    <header class="topbar">
      <div class="brand" @click="router.push('/dashboard')">
        <span class="logo">🌿</span> BalanceWise
      </div>

      <nav class="menu">
        <button
          v-for="item in menus"
          :key="item.path"
          class="menu-item"
          :class="{ active: isActive(item.path) }"
          @click="router.push(item.path)"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span class="label">{{ item.label }}</span>
        </button>
      </nav>

      <div class="actions">
        <el-dropdown trigger="click" @command="theme.setTheme">
          <button class="icon-btn" title="切换主题">
            <el-icon><Brush /></el-icon>
            <span class="dot" :style="{ background: currentTheme.dot }" />
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item
                v-for="t in theme.themes"
                :key="t.id"
                :command="t.id"
                :class="{ 'theme-active': t.id === theme.current }"
              >
                <span class="theme-dot" :style="{ background: t.dot }" />
                <span class="theme-name">{{ t.name }}</span>
                <span class="theme-desc">{{ t.desc }}</span>
                <el-icon v-if="t.id === theme.current" class="check"><Check /></el-icon>
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>

        <el-dropdown trigger="click" @command="onUserCommand">
          <button class="avatar" :title="store.user?.nickname || '我'">
            {{ (store.user?.nickname || '我').slice(0, 1) }}
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item disabled>
                <span class="nick">{{ store.user?.nickname }}</span>
              </el-dropdown-item>
              <el-dropdown-item command="user" divided>
                <el-icon><User /></el-icon>个人信息
              </el-dropdown-item>
              <el-dropdown-item command="logout">
                <el-icon><SwitchButton /></el-icon>退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <main class="content">
      <router-view v-slot="{ Component }">
        <transition name="bw-route" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </main>

    <button
      v-if="$route.path !== '/record'"
      class="fab"
      title="记一笔"
      @click="router.push('/record')"
    >
      ✏️ <span>记一笔</span>
    </button>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import { useThemeStore } from '../stores/theme'
import {
  HomeFilled, EditPen, Notebook, Download, CreditCard, CollectionTag, DataAnalysis,
  Brush, Check, User, SwitchButton,
} from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const store = useUserStore()
const theme = useThemeStore()

const menus = [
  { path: '/dashboard', label: '首页', icon: HomeFilled },
  { path: '/transactions', label: '流水', icon: Notebook },
  { path: '/import', label: '导入', icon: Download },
  { path: '/accounts', label: '账户', icon: CreditCard },
  { path: '/categories', label: '分类', icon: CollectionTag },
  { path: '/stats', label: '统计', icon: DataAnalysis },
]

const isActive = path => route.path === path || (path !== '/dashboard' && route.path.startsWith(path))
const currentTheme = computed(() => theme.themes.find(t => t.id === theme.current) || theme.themes[0])

function onUserCommand(cmd) {
  if (cmd === 'logout') {
    store.logout()
    router.push('/login')
  } else if (cmd === 'user') {
    router.push('/user')
  }
}
</script>

<style scoped>
.shell { min-height: 100vh; background: var(--bw-bg); }

.topbar {
  position: sticky; top: 0; z-index: 100;
  display: flex; align-items: center; gap: 24px;
  padding: 10px 24px;
  background: color-mix(in srgb, var(--bw-card) 82%, transparent);
  backdrop-filter: blur(10px);
  border-bottom: 1px solid var(--bw-border);
}
.brand {
  font-size: 19px; font-weight: 800; cursor: pointer; white-space: nowrap;
  display: flex; align-items: center; gap: 6px;
}
.brand .logo { font-size: 22px; }

.menu { display: flex; gap: 4px; flex: 1; overflow-x: auto; }
.menu-item {
  display: inline-flex; align-items: center; gap: 6px;
  border: none; cursor: pointer; white-space: nowrap;
  padding: 8px 14px; border-radius: 999px;
  background: transparent; color: var(--bw-text-secondary);
  font-size: 14px; font-family: inherit;
  transition: all 0.18s ease;
}
.menu-item:hover { color: var(--bw-primary); background: var(--bw-primary-soft); }
.menu-item.active {
  background: var(--bw-primary); color: #fff;
  font-weight: 600; box-shadow: 0 4px 12px color-mix(in srgb, var(--bw-primary) 40%, transparent);
}

.actions { display: flex; align-items: center; gap: 10px; }
.icon-btn {
  position: relative;
  display: inline-flex; align-items: center; justify-content: center;
  width: 38px; height: 38px; border-radius: 50%;
  border: 1px solid var(--bw-border); background: var(--bw-card);
  color: var(--bw-text); cursor: pointer; font-size: 16px;
  transition: all 0.18s ease;
}
.icon-btn:hover { border-color: var(--bw-primary); color: var(--bw-primary); }
.icon-btn .dot {
  position: absolute; right: -2px; bottom: -2px;
  width: 12px; height: 12px; border-radius: 50%;
  border: 2px solid var(--bw-card);
}
.avatar {
  width: 38px; height: 38px; border: none; border-radius: 50%;
  background: var(--bw-hero-gradient); color: #fff;
  font-size: 16px; font-weight: 700; cursor: pointer;
  box-shadow: 0 4px 12px color-mix(in srgb, var(--bw-primary) 40%, transparent);
}

.content { padding-bottom: 96px; }

/* 路由切换轻过渡：掩掉硬切的"闪一下"，保持敏捷（总时长 ~0.28s） */
.bw-route-enter-active,
.bw-route-leave-active { transition: opacity 0.14s ease, transform 0.14s ease; }
.bw-route-enter-from { opacity: 0; transform: translateY(6px); }
.bw-route-leave-to { opacity: 0; }

/* 悬浮记账按钮 */
.fab {
  position: fixed; right: 28px; bottom: 28px; z-index: 90;
  display: inline-flex; align-items: center; gap: 8px;
  padding: 15px 22px; border: none; border-radius: 999px;
  background: var(--bw-hero-gradient); color: #fff;
  font-size: 16px; font-weight: 700; font-family: inherit;
  cursor: pointer; box-shadow: 0 10px 26px color-mix(in srgb, var(--bw-primary) 50%, transparent);
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}
.fab:hover { transform: translateY(-2px) scale(1.03); }
.fab:active { transform: scale(0.97); }

.theme-dot { width: 14px; height: 14px; border-radius: 50%; margin-right: 8px; }
.theme-name { margin-right: 8px; }
.theme-desc { color: var(--bw-text-secondary); font-size: 12px; }
.check { margin-left: auto; color: var(--bw-primary); }
.nick { font-size: 13px; }

@media (max-width: 760px) {
  .menu-item .label { display: none; }
  .menu-item { padding: 8px 11px; }
  .topbar { gap: 12px; padding: 10px 14px; }
  .fab span { display: none; }
  .fab { padding: 16px; }
}
</style>
