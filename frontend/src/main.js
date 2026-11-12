import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import App from './App.vue'

// 过滤 ResizeObserver loop 良性报错：该报错是浏览器对「观察回调内触发布局变化」的通知，
// 在 Vue/ElementPlus 响应式重渲染场景下反复出现，不影响功能；按 message 白名单忽略，
// 其余真实错误仍走浏览器默认处理，不掩盖。
function isBenignResizeObserverError(message) {
  return /ResizeObserver loop (completed with undelivered notifications|limit exceeded)/i.test(
    String(message || '')
  )
}
window.addEventListener('error', (event) => {
  if (isBenignResizeObserverError(event && event.message)) {
    event.preventDefault()
  }
})
window.addEventListener('unhandledrejection', (event) => {
  const reason = event && event.reason
  const message = reason && reason.message ? reason.message : String(reason || '')
  if (isBenignResizeObserverError(message)) {
    event.preventDefault()
  }
})

const app = createApp(App)
app.use(ElementPlus)
app.mount('#app')
