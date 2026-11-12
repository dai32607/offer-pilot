import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // 前端 /api 请求代理到后端 8083（S6 面试模块实例）；SSE 需禁用缓冲以保持长连接
      '/api': {
        target: 'http://localhost:8083',
        changeOrigin: true,
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes) => {
            proxyRes.headers['cache-control'] = 'no-cache, no-transform'
          })
        }
      }
    }
  }
})
