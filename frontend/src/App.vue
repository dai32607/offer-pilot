<template>
  <div class="page">
    <header class="page-header">
      <div>
        <h1>OfferPilot</h1>
      </div>
      <div class="task-no" v-if="taskNo">
        <span class="label">taskNo</span>
        <span class="value">{{ taskNo }}</span>
        <el-tag size="small" :type="statusTagType" effect="light">{{ statusText }}</el-tag>
      </div>
      <el-dropdown trigger="click" @command="onThemeCommand">
        <el-button size="small" circle class="theme-btn" title="切换主题">
          <span class="theme-icon">{{ themeIcon }}</span>
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="system" :class="{ 'theme-active': themeMode === 'system' }">
              跟随系统
            </el-dropdown-item>
            <el-dropdown-item command="light" :class="{ 'theme-active': themeMode === 'light' }">
              亮色模式
            </el-dropdown-item>
            <el-dropdown-item command="dark" :class="{ 'theme-active': themeMode === 'dark' }">
              暗色模式
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </header>

    <el-row :gutter="16">
      <el-col :xs="24" :md="10">
        <el-card shadow="never" class="input-card">
          <template #header><span class="section-title">输入</span></template>

          <div class="field">
            <div class="field-label">JD 原文</div>
            <div
              class="drop-zone"
              :class="{ 'drop-active': dragTarget === 'jd' }"
              @dragover.prevent="onDragOver('jd')"
              @dragleave="onDragLeave('jd')"
              @drop.prevent="onDrop($event, 'jd')"
            >
              <el-input v-model="jdText" type="textarea" :rows="9" placeholder="粘贴 JD 原文（职责 + 任职要求）" />
              <div class="drop-footer">
                <span v-if="jdFile" class="file-preview">
                  <span class="file-type">{{ jdFile.ext }}</span>
                  <span class="file-name" :title="jdFile.name">{{ jdFile.name }}</span>
                  <span class="file-size">{{ jdFile.sizeText }}</span>
                  <el-button size="small" text type="danger" @click="clearFile('jd')">移除</el-button>
                </span>
                <span v-else class="drop-hint">将 .txt / .md / .json / .csv 等文本文件拖入此处，或</span>
                <el-button size="small" @click="pickFile('jd')">选择文件</el-button>
              </div>
            </div>
          </div>

          <div class="field">
            <div class="field-label">简历原文</div>
            <div
              class="drop-zone"
              :class="{ 'drop-active': dragTarget === 'resume' }"
              @dragover.prevent="onDragOver('resume')"
              @dragleave="onDragLeave('resume')"
              @drop.prevent="onDrop($event, 'resume')"
            >
              <el-input v-model="resumeText" type="textarea" :rows="12" placeholder="粘贴简历原文，或将 .txt / .md 等文本文件拖入此处" />
              <div class="drop-footer">
                <span v-if="resumeFile" class="file-preview">
                  <span class="file-type">{{ resumeFile.ext }}</span>
                  <span class="file-name" :title="resumeFile.name">{{ resumeFile.name }}</span>
                  <span class="file-size">{{ resumeFile.sizeText }}</span>
                  <el-button size="small" text type="danger" @click="clearFile('resume')">移除</el-button>
                </span>
                <span v-else class="drop-hint">将 .txt / .md / .json / .csv 等文本文件拖入此处，或</span>
                <el-button size="small" @click="pickFile('resume')">选择文件</el-button>
              </div>
            </div>
          </div>

          <div class="actions">
            <el-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="start">
              开始分析
            </el-button>
            <el-button :disabled="submitting" @click="loadSample">载入示例样本</el-button>
          </div>

          <div class="actions">
            <el-button size="small" :disabled="!isRunning" @click="disconnect">模拟断线</el-button>
            <el-button size="small" :disabled="!isRunning" @click="reconnect">重连（SSE 补齐）</el-button>
            <el-button size="small" :disabled="!taskNo" @click="reconcile(true)">REST 补齐对账</el-button>
          </div>

          <el-alert
            v-if="errorMsg"
            :title="errorMsg"
            type="error"
            :closable="false"
            show-icon
            class="mt"
          />

          <p class="tip">
            断线续传：EventSource 断开后由浏览器自动重连并携带 Last-Event-ID，服务端只补发缺失 seq 的事件；
            「REST 补齐对账」通过 /events 接口按 seq 补齐，保证事件不重不漏。
          </p>
        </el-card>

        <el-card shadow="never" class="placeholder-card">
          <template #header><span class="section-title">改写建议</span></template>
          <div
            v-if="report && report.rewriteSuggestions && report.rewriteSuggestions.length"
            class="rewrite-list"
          >
            <div
              v-for="(s, idx) in report.rewriteSuggestions"
              :key="idx"
              class="rewrite-item"
            >
              <div class="rewrite-head">
                <span class="rewrite-index">{{ idx + 1 }}</span>
                <span class="rewrite-ref" v-if="s.reference">{{ s.reference }}</span>
                <span class="rewrite-ref" v-else>简历表述优化</span>
              </div>
              <div class="rewrite-row">
                <div class="rewrite-cell original">
                  <div class="rewrite-cell-label">原文片段</div>
                  <div class="rewrite-cell-text">{{ s.original }}</div>
                </div>
                <div class="rewrite-arrow">→</div>
                <div class="rewrite-cell rewritten">
                  <div class="rewrite-cell-label">改写建议</div>
                  <div class="rewrite-cell-text">{{ s.rewritten }}</div>
                </div>
              </div>
              <div v-if="s.reason" class="rewrite-reason">
                理由：{{ s.reason }}
              </div>
            </div>
          </div>
          <el-empty
            v-else
            description="暂无改写建议：评估完成后将基于报告与简历原文生成表述优化建议"
            :image-size="60"
          />
        </el-card>

        <InterviewPanel :task-no="taskNo" :ready="interviewReady" />
      </el-col>

      <el-col :xs="24" :md="14">
        <AgentTimeline
          :timeline="timeline"
          :events="events"
          :progress="progress"
          :status="status"
          :connection-state="connectionState"
          :last-seq="lastSeq"
          :last-heartbeat="lastHeartbeat"
          :reconnect-count="reconnectCount"
          :gap-filled-count="gapFilledCount"
        />

        <ReportPanel :report="report" :meta="meta" :loading="isRunning" />

        <ReplayPanel />

        <el-card shadow="never" class="events-card">
          <template #header><span class="section-title">事件流（按 seq 升序）</span></template>
          <el-table :data="events" size="small" max-height="260" empty-text="暂无事件">
            <el-table-column prop="seq" label="seq" width="70" />
            <el-table-column prop="name" label="事件" width="150" />
            <el-table-column prop="ts" label="时间" width="200" />
            <el-table-column label="关键数据" min-width="240">
              <template #default="{ row }">
                <span class="event-data">{{ summarize(row) }}</span>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import AgentTimeline from './components/AgentTimeline.vue'
import ReportPanel from './components/ReportPanel.vue'
import InterviewPanel from './components/InterviewPanel.vue'
import ReplayPanel from './components/ReplayPanel.vue'
import { useAnalysisTask } from './composables/useAnalysisTask'

// ---------- 主题：跟随系统 / 亮色 / 暗色（CSS 变量 + html.dark） ----------
const themeMode = ref(localStorage.getItem('op-theme') || 'system')
const prefersDarkMedia = window.matchMedia('(prefers-color-scheme: dark)')

function applyTheme() {
  const dark = themeMode.value === 'dark' ||
    (themeMode.value === 'system' && prefersDarkMedia.matches)
  document.documentElement.classList.toggle('dark', dark)
}
applyTheme()

watch(themeMode, (v) => {
  localStorage.setItem('op-theme', v)
  applyTheme()
})

function onMediaChange() {
  if (themeMode.value === 'system') applyTheme()
}
prefersDarkMedia.addEventListener('change', onMediaChange)
onBeforeUnmount(() => prefersDarkMedia.removeEventListener('change', onMediaChange))

function onThemeCommand(cmd) {
  themeMode.value = cmd
  ElMessage.success({ system: '主题：跟随系统', light: '主题：亮色模式', dark: '主题：暗色模式' }[cmd])
}

const themeIcon = computed(() => {
  if (themeMode.value === 'dark') return '暗'
  if (themeMode.value === 'light') return '亮'
  return '系统'
})

const jdText = ref('')
const resumeText = ref('')

// ---------- 上传区：拖拽 / 选择文件 + 文件预览（文件名 / 大小 / 类型） ----------
const jdFile = ref(null)
const resumeFile = ref(null)
const dragTarget = ref('')
const TEXT_EXTS = ['txt', 'md', 'markdown', 'json', 'csv', 'log', 'yaml', 'yml', 'xml', 'html']

function onDragOver(target) {
  dragTarget.value = target
}

function onDragLeave(target) {
  if (dragTarget.value === target) dragTarget.value = ''
}

function pickFile(target) {
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = '.txt,.md,.markdown,.json,.csv,.log,.yaml,.yml,.xml,.html'
  input.onchange = () => {
    const f = input.files && input.files[0]
    if (f) handleFile(f, target)
    input.value = ''
  }
  input.click()
}

async function onDrop(e, target) {
  dragTarget.value = ''
  const f = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0]
  if (f) await handleFile(f, target)
}

async function handleFile(f, target) {
  const meta = fileMeta(f)
  if (target === 'jd') jdFile.value = meta
  else resumeFile.value = meta
  if (TEXT_EXTS.includes(meta.ext)) {
    try {
      const text = await f.text()
      if (target === 'jd') jdText.value = text
      else resumeText.value = text
      ElMessage.success(`已读取 ${meta.name} 内容（${meta.sizeText}）`)
    } catch (e) {
      ElMessage.error('文件读取失败，请手动粘贴文本')
    }
  } else {
    ElMessage.warning(`暂不支持在浏览器解析 .${meta.ext} 文件，已记录文件信息，请手动粘贴文本内容`)
  }
}

function fileMeta(f) {
  const name = f.name
  const dot = name.lastIndexOf('.')
  const ext = dot >= 0 ? name.slice(dot + 1).toLowerCase() : 'file'
  const size = f.size
  const sizeText = size >= 1048576
    ? `${(size / 1048576).toFixed(2)} MB`
    : size >= 1024 ? `${(size / 1024).toFixed(1)} KB` : `${size} B`
  return { name, ext, size, sizeText }
}

function clearFile(target) {
  if (target === 'jd') jdFile.value = null
  else resumeFile.value = null
}

const {
  taskNo,
  status,
  progress,
  currentAgent,
  failedAgent,
  tokenTotal,
  durationMs,
  errorMsg,
  events,
  report,
  meta,
  submitting,
  connectionState,
  lastSeq,
  lastHeartbeat,
  reconnectCount,
  gapFilledCount,
  timeline,
  isRunning,
  submit,
  disconnect,
  reconnect,
  reconcile
} = useAnalysisTask()

const canSubmit = computed(() => jdText.value.trim().length > 0 && resumeText.value.trim().length > 0)

const interviewReady = computed(() => Boolean(report.value) && Boolean(taskNo.value))

const statusText = computed(() => {
  if (!status.value) return '待提交'
  return {
    RUNNING: currentAgent.value ? `执行中 · ${currentAgent.value}` : '执行中',
    SUCCEEDED: '已完成',
    FAILED: failedAgent.value ? `失败 · ${failedAgent.value}` : '失败'
  }[status.value] || status.value
})

const statusTagType = computed(() => {
  if (status.value === 'SUCCEEDED') return 'success'
  if (status.value === 'FAILED') return 'danger'
  if (status.value === 'RUNNING') return 'primary'
  return 'info'
})

async function start() {
  try {
    await submit(jdText.value, resumeText.value)
    ElMessage.success('任务已提交，正在接收进度事件')
  } catch (e) {
    ElMessage.error(e.message || '任务提交失败')
  }
}

async function loadSample() {
  try {
    const [jd, resume] = await Promise.all([
      fetch('/samples/jd-sample.md').then((r) => r.text()),
      fetch('/samples/resume-sample.md').then((r) => r.text())
    ])
    jdText.value = jd
    resumeText.value = resume
    ElMessage.success('已载入示例样本')
  } catch (e) {
    ElMessage.error('示例样本载入失败，请手动粘贴文本')
  }
}

function summarize(row) {
  const d = row.data || {}
  const parts = []
  if (d.agent_name) parts.push(d.agent_name)
  if (d.model) parts.push(d.model)
  if (typeof d.latency_ms === 'number') parts.push(`${(d.latency_ms / 1000).toFixed(1)}s`)
  if (typeof d.elapsed_ms === 'number') parts.push(`已耗时 ${(d.elapsed_ms / 1000).toFixed(1)}s`)
  if (typeof d.total_tokens === 'number') parts.push(`token ${d.total_tokens}`)
  if (typeof d.progress === 'number') parts.push(`${d.progress}%`)
  if (typeof d.overall_score === 'number') parts.push(`总分 ${d.overall_score} / ${d.level}`)
  if (d.status) parts.push(d.status)
  if (typeof d.token_total === 'number') parts.push(`token 合计 ${d.token_total}`)
  if (typeof d.duration_ms === 'number') parts.push(`总耗时 ${(d.duration_ms / 1000).toFixed(1)}s`)
  if (d.error_code) parts.push(`错误码 ${d.error_code}`)
  return parts.join(' · ') || '-'
}
</script>

<style>
/* ---------- 全局 CSS 变量（亮色默认 / 暗色 html.dark 覆盖） ---------- */
:root {
  --op-bg-page: #f5f7fa;
  --op-text-primary: #303133;
  --op-text-regular: #606266;
  --op-text-secondary: #909399;
  --op-border-light: #ebeef5;
  --op-border-base: #e4e7ed;
  --op-border-dashed: #ebeef5;
  --op-bubble-interviewer-bg: #f0f6ff;
  --op-bubble-interviewer-border: #d6e4ff;
  --op-bubble-candidate-bg: #f6f8fa;
  --op-bubble-candidate-border: #e4e7ed;
  --op-eval-bg: #ffffff;
  --op-strip-bg: #f6f8fa;
  --op-replay-eval-bg: #fdf6ec;
  --op-replay-eval-border: #faecd8;
  --op-replay-eval-color: #b88230;
}
html.dark {
  --op-bg-page: #141414;
  --op-text-primary: #e5eaf3;
  --op-text-regular: #cfd3dc;
  --op-text-secondary: #a3a6ad;
  --op-border-light: #363637;
  --op-border-base: #414243;
  --op-border-dashed: #363637;
  --op-bubble-interviewer-bg: #1c2837;
  --op-bubble-interviewer-border: #2c4059;
  --op-bubble-candidate-bg: #1d1e1f;
  --op-bubble-candidate-border: #363637;
  --op-eval-bg: #1d1e1f;
  --op-strip-bg: #1d1e1f;
  --op-replay-eval-bg: #2a2318;
  --op-replay-eval-border: #4a3a20;
  --op-replay-eval-color: #e6c46a;
}
body {
  margin: 0;
  background: var(--op-bg-page);
  font-family: 'Helvetica Neue', Helvetica, 'PingFang SC', 'Microsoft YaHei', Arial, sans-serif;
}
</style>

<style scoped>
.page {
  max-width: 1440px;
  margin: 0 auto;
  padding: 20px 24px 48px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.page-header h1 {
  margin: 0;
  font-size: 24px;
  color: var(--op-text-primary);
}

.subtitle {
  margin: 6px 0 0;
  color: var(--op-text-secondary);
  font-size: 13px;
}

.task-no {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.task-no .label {
  color: var(--op-text-secondary);
}

.task-no .value {
  font-family: Consolas, Monaco, monospace;
  color: var(--op-text-primary);
}

.theme-btn {
  font-size: 12px;
}

.theme-icon {
  font-size: 12px;
  letter-spacing: 0;
}

.theme-active {
  color: var(--el-color-primary);
  font-weight: 600;
}

.section-title {
  font-weight: 600;
}

.input-card,
.placeholder-card {
  margin-bottom: 16px;
}

.rewrite-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.rewrite-item {
  border: 1px solid var(--op-border-base);
  border-radius: 8px;
  padding: 10px 12px;
  background: var(--op-eval-bg);
}

.rewrite-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.rewrite-index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  font-size: 12px;
  color: var(--el-color-primary);
  background: var(--op-strip-bg);
  border: 1px solid var(--op-border-base);
}

.rewrite-ref {
  font-size: 12px;
  color: var(--op-text-secondary);
  background: var(--op-strip-bg);
  border: 1px solid var(--op-border-base);
  border-radius: 4px;
  padding: 1px 8px;
}

.rewrite-row {
  display: flex;
  align-items: stretch;
  gap: 10px;
}

.rewrite-cell {
  flex: 1;
  min-width: 0;
  border: 1px solid var(--op-border-dashed);
  border-radius: 6px;
  padding: 8px 10px;
}

.rewrite-cell.original {
  background: var(--op-replay-eval-bg);
  border-color: var(--op-replay-eval-border);
}

.rewrite-cell.rewritten {
  background: var(--op-eval-bg);
}

.rewrite-cell-label {
  font-size: 12px;
  color: var(--op-text-secondary);
  margin-bottom: 4px;
}

.rewrite-cell-text {
  font-size: 13px;
  line-height: 1.55;
  color: var(--op-text-primary);
  word-break: break-all;
  white-space: pre-wrap;
}

.rewrite-cell.original .rewrite-cell-text {
  color: var(--op-replay-eval-color);
}

.rewrite-arrow {
  display: flex;
  align-items: center;
  color: var(--op-text-secondary);
  font-size: 16px;
  flex-shrink: 0;
}

.rewrite-reason {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--op-text-regular);
  background: var(--op-strip-bg);
  border-radius: 4px;
  padding: 6px 8px;
}

.field {
  margin-bottom: 14px;
}

.field-label {
  font-size: 13px;
  color: var(--op-text-regular);
  margin-bottom: 6px;
}

/* ---------- 拖拽上传区（虚线框 + 悬停高亮，暗色兼容） ---------- */
.drop-zone {
  border: 1.5px dashed var(--op-border-base);
  border-radius: 8px;
  padding: 8px;
  background: var(--op-strip-bg);
  transition: border-color 0.2s ease, background 0.2s ease;
}

.drop-zone.drop-active {
  border-color: var(--el-color-primary);
  background: color-mix(in srgb, var(--el-color-primary) 6%, transparent);
}

.drop-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 6px;
  flex-wrap: wrap;
}

.drop-hint {
  font-size: 12px;
  color: var(--op-text-secondary);
}

.file-preview {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  background: var(--op-eval-bg);
  border: 1px solid var(--op-border-light);
  border-radius: 6px;
  padding: 3px 6px 3px 8px;
  max-width: 100%;
}

.file-type {
  flex: none;
  font-size: 11px;
  font-weight: 700;
  text-transform: uppercase;
  color: var(--el-color-primary);
  background: color-mix(in srgb, var(--el-color-primary) 12%, transparent);
  border-radius: 4px;
  padding: 1px 6px;
}

.file-name {
  color: var(--op-text-primary);
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-size {
  color: var(--op-text-secondary);
  flex: none;
}

.actions {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}

.mt {
  margin-top: 10px;
}

.tip {
  color: var(--op-text-secondary);
  font-size: 12px;
  line-height: 1.7;
  margin: 12px 0 0;
  border-top: 1px dashed var(--op-border-light);
  padding-top: 10px;
}

.events-card {
  margin-bottom: 16px;
}

.event-data {
  font-size: 12px;
  color: var(--op-text-regular);
}
</style>
