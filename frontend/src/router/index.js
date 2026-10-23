import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/login', component: () => import('../views/LoginView.vue') },
  { path: '/register', component: () => import('../views/RegisterView.vue') },
  {
    path: '/',
    component: () => import('../layouts/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', component: () => import('../views/DashboardView.vue'), meta: { title: '首页仪表盘' } },
      { path: 'record', component: () => import('../views/RecordView.vue'), meta: { title: '记一笔' } },
      { path: 'transactions', component: () => import('../views/TransactionsView.vue'), meta: { title: '流水' } },
      { path: 'import', component: () => import('../views/ImportView.vue'), meta: { title: '账单导入' } },
      { path: 'accounts', component: () => import('../views/AccountView.vue'), meta: { title: '账户管理' } },
      { path: 'categories', component: () => import('../views/CategoryView.vue'), meta: { title: '分类管理' } },
      { path: 'stats', component: () => import('../views/StatsView.vue'), meta: { title: '统计报表' } },
      { path: 'user', component: () => import('../views/UserCenterView.vue'), meta: { title: '用户中心' } },
    ],
  },
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach(to => {
  const token = localStorage.getItem('bw_token')
  if (!token && to.path !== '/login' && to.path !== '/register') return '/login'
  if (token && (to.path === '/login' || to.path === '/register')) return '/dashboard'
  return true
})

export default router
