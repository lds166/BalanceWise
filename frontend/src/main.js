import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'

// 统一兜底未处理的 Promise 拒绝：request.js 响应拦截器已统一弹出后端错误消息，
// 这里只阻止浏览器控制台 unhandledrejection 噪音（台账 #14，覆盖 Dashboard/Stats/Import 三页）
window.addEventListener('unhandledrejection', e => e.preventDefault())

createApp(App)
  .use(createPinia())
  .use(router)
  .use(ElementPlus)
  .mount('#app')
