// 临时演示校验配置（S7 时延治理验证用，非项目正式配置）：代理指向新代码实例 8082。
// 验证完成后由 Agent 删除本文件，不影响项目原有 vite.config.js。
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5273,
    proxy: {
      '/api': {
        target: 'http://localhost:8082',
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
