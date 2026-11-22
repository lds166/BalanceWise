import { createRouter, createWebHistory } from 'vue-router'
import MainLayout from '../layouts/MainLayout.vue'
import DashboardView from '../views/DashboardView.vue'
import RecordView from '../views/RecordView.vue'
import TransactionsView from '../views/TransactionsView.vue'
import ImportView from '../views/ImportView.vue'
import AccountView from '../views/AccountView.vue'
import CategoryView from '../views/CategoryView.vue'
import StatsView from '../views/StatsView.vue'
import UserCenterView from '../views/UserCenterView.vue'
import LoginView from '../views/LoginView.vue'
import RegisterView from '../views/RegisterView.vue'

// 路由组件全部预加载（应用很小）：避免首次进入页面时等待 chunk 造成路由切换空白闪屏
const routes = [
  { path: '/login', component: LoginView },
  { path: '/register', component: RegisterView },
  {
    path: '/',
    component: MainLayout,
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', component: DashboardView, meta: { title: '首页仪表盘' } },
      { path: 'record', component: RecordView, meta: { title: '记一笔' } },
      { path: 'transactions', component: TransactionsView, meta: { title: '流水' } },
      { path: 'import', component: ImportView, meta: { title: '账单导入' } },
      { path: 'accounts', component: AccountView, meta: { title: '账户管理' } },
      { path: 'categories', component: CategoryView, meta: { title: '分类管理' } },
      { path: 'stats', component: StatsView, meta: { title: '统计报表' } },
      { path: 'user', component: UserCenterView, meta: { title: '个人信息' } },
    ],
  },
]

const router = createRouter({ history: createWebHistory(), routes, scrollBehavior: () => ({ top: 0 }) })

router.beforeEach(to => {
  const token = localStorage.getItem('bw_token')
  if (!token && to.path !== '/login' && to.path !== '/register') return '/login'
  if (token && (to.path === '/login' || to.path === '/register')) return '/dashboard'
  return true
})

export default router
