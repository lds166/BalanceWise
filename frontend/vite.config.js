import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  build: {
    rollupOptions: {
      output: {
        // 台账 #16：echarts/element-plus 全量引入改走 manualChunks 独立分包，
        // 业务 chunk 不再携带图表与组件库（不改 useChart import 语义，构建通过即可）
        manualChunks: {
          echarts: ['echarts'],
          element: ['element-plus', '@element-plus/icons-vue'],
        },
      },
    },
  },
})
