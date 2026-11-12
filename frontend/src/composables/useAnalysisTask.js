import { computed, ref } from 'vue'
import { createTask, fetchEvents, fetchReport, fetchTaskStatus, streamUrl } from '../api/api'

/** 后端可推送的全部 SSE 事件名（需求说明书 6.4）。 */
const EVENT_NAMES = [
  'task.started',
  'agent.started',
  'agent.progress',
  'agent.finished',
  'agent.failed',
  'report.ready',
  'task.finished',
  'heartbeat'
]

export const AGENT_LABELS = {
  JD_PARSE: 'JD 解析',
  RESUME_PARSE: '简历解析',
  MATCH_EVAL: '匹配评估'
}

const AGENT_ORDER = ['JD_PARSE', 'RESUME_PARSE', 'MATCH_EVAL']

/**
 * 任务提交 + SSE 消费 + 断线补齐的前端状态机。
 * <p>
 * 事件按 seq 去重并升序排列：无论来自 SSE 实时推送、浏览器自动重连补发，
 * 还是 REST 补齐通道，最终事件序列都保持「按序、不重、不漏」。
 */
export function useAnalysisTask() {
  const taskNo = ref('')
  const status = ref('')
  const progress = ref(0)
  const currentAgent = ref('')
  const failedAgent = ref('')
  const tokenTotal = ref(0)
  const durationMs = ref(0)
  const level = ref('')
  const scorePreview = ref(null)

  const events = ref([])
  const report = ref(null)
  const meta = ref(null)
  const errorMsg = ref('')
  const submitting = ref(false)

  const connectionState = ref('idle') // idle | connecting | open | reconnecting | disconnected | closed
  const lastSeq = ref(0)
  const lastHeartbeat = ref('')
  const reconnectCount = ref(0)
  const gapFilledCount = ref(0)
  const finishedCount = ref(0)

  let source = null
  const seen = new Set()

  const timeline = computed(() => {
    const nodes = {}
    const order = []
    const ensure = (name) => {
      if (!nodes[name]) {
        nodes[name] = {
          name,
          label: AGENT_LABELS[name] || name,
          state: 'pending',
          model: '',
          latencyMs: null,
          tokens: null,
          elapsedMs: null,
          attempts: null,
          retried: false,
          errorCode: ''
        }
        order.push(name)
      }
      return nodes[name]
    }
    for (const ev of events.value) {
      const d = ev.data || {}
      if (ev.name === 'agent.started') {
        const n = ensure(d.agent_name)
        n.state = 'running'
        n.model = d.model || ''
      } else if (ev.name === 'agent.progress') {
        const n = ensure(d.agent_name)
        if (n.state === 'running') {
          n.elapsedMs = d.elapsed_ms
        }
      } else if (ev.name === 'agent.finished') {
        const n = ensure(d.agent_name)
        n.state = 'done'
        n.latencyMs = d.latency_ms
        n.tokens = d.total_tokens
        n.attempts = d.attempts
        n.retried = !!d.retried
        n.elapsedMs = null
      } else if (ev.name === 'agent.failed') {
        const n = ensure(d.agent_name)
        n.state = 'failed'
        n.errorCode = d.error_code
      }
    }
    // 未出现在事件流中的 Agent 也占位展示，保证时间线稳定
    AGENT_ORDER.forEach((name) => ensure(name))
    return order.map((k) => nodes[k])
  })

  const isRunning = computed(() => status.value === 'RUNNING')
  const isFinished = computed(() => status.value === 'SUCCEEDED' || status.value === 'FAILED')

  function reset() {
    closeStream()
    taskNo.value = ''
    status.value = ''
    progress.value = 0
    currentAgent.value = ''
    failedAgent.value = ''
    tokenTotal.value = 0
    durationMs.value = 0
    level.value = ''
    scorePreview.value = null
    events.value = []
    report.value = null
    meta.value = null
    errorMsg.value = ''
    connectionState.value = 'idle'
    lastSeq.value = 0
    lastHeartbeat.value = ''
    reconnectCount.value = 0
    gapFilledCount.value = 0
    finishedCount.value = 0
    seen.clear()
  }

  async function submit(jdText, resumeText) {
    reset()
    submitting.value = true
    try {
      const created = await createTask({
        taskNo: genTaskNo(),
        jdText,
        resumeText,
        options: { needRewrite: true, needInterview: false }
      })
      taskNo.value = created.taskNo
      status.value = created.status
      progress.value = created.progress
      openStream()
      return created
    } catch (e) {
      errorMsg.value = e.message
      throw e
    } finally {
      submitting.value = false
    }
  }

  function openStream() {
    if (!taskNo.value) {
      return
    }
    closeStream()
    connectionState.value = events.value.length > 0 ? 'reconnecting' : 'connecting'
    source = new EventSource(streamUrl(taskNo.value))
    source.onopen = () => {
      connectionState.value = 'open'
    }
    source.onerror = () => {
      // EventSource 会自动重连，并在重连请求带上 Last-Event-ID
      if (connectionState.value === 'open') {
        reconnectCount.value += 1
      }
      connectionState.value = 'reconnecting'
    }
    EVENT_NAMES.forEach((name) => {
      source.addEventListener(name, (ev) => handleEvent(name, ev))
    })
  }

  function closeStream() {
    if (source) {
      source.close()
      source = null
    }
  }

  function handleEvent(name, ev) {
    if (name === 'heartbeat') {
      lastHeartbeat.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
      return
    }
    let data
    try {
      data = JSON.parse(ev.data)
    } catch (e) {
      return
    }
    mergeEvent(name, data)
  }

  /** 按 seq 去重合并一条事件，并在合并后驱动状态机。 */
  function mergeEvent(name, data) {
    if (typeof data.seq !== 'number' || seen.has(data.seq)) {
      return
    }
    seen.add(data.seq)
    events.value.push({ seq: data.seq, name, data, ts: data.ts })
    events.value.sort((a, b) => a.seq - b.seq)
    lastSeq.value = Math.max(lastSeq.value, data.seq)
    applyEvent(name, data)
  }

  function applyEvent(name, data) {
    if (name === 'task.started') {
      status.value = 'RUNNING'
      progress.value = 0
    } else if (name === 'agent.started') {
      currentAgent.value = data.agent_name
    } else if (name === 'agent.finished') {
      finishedCount.value += 1
      progress.value = Math.min(99, Math.round((finishedCount.value / AGENT_ORDER.length) * 100))
      currentAgent.value = ''
    } else if (name === 'agent.failed') {
      failedAgent.value = data.agent_name
      errorMsg.value = `节点 ${data.agent_name} 失败（错误码 ${data.error_code}，重试 ${data.retry_count} 次）`
    } else if (name === 'report.ready') {
      scorePreview.value = data.overall_score
      level.value = data.level
      progress.value = 100
    } else if (name === 'task.finished') {
      status.value = data.status
      tokenTotal.value = data.token_total || 0
      durationMs.value = data.duration_ms || 0
      progress.value = 100
      currentAgent.value = ''
      closeStream()
      connectionState.value = 'closed'
      if (data.status === 'SUCCEEDED') {
        loadReport()
      }
    }
  }

  async function loadReport() {
    if (!taskNo.value) {
      return
    }
    try {
      const data = await fetchReport(taskNo.value)
      report.value = data.report
      meta.value = data.meta
    } catch (e) {
      errorMsg.value = e.message
    }
  }

  /** 模拟断线：主动关闭 SSE 连接（重连后由服务端按 Last-Event-ID 补齐缺失事件）。 */
  function disconnect() {
    closeStream()
    connectionState.value = 'disconnected'
  }

  /** 重连：重新建立 SSE 连接；服务端补发 seq > Last-Event-ID 的事件。 */
  function reconnect() {
    reconnectCount.value += 1
    openStream()
    refreshStatus()
  }

  /**
   * REST 补齐通道：拉取 seq > 本地最大 seq 的事件并合并，
   * 用于验证「断线期间遗漏的事件能被补齐」。
   */
  async function reconcile(trackGap = true) {
    if (!taskNo.value) {
      return
    }
    const before = lastSeq.value
    try {
      const list = await fetchEvents(taskNo.value, lastSeq.value)
      list.forEach((item) => mergeEvent(item.event, item.data))
      if (trackGap) {
        gapFilledCount.value += list.filter((item) => item.seq > before).length
      }
      await refreshStatus()
    } catch (e) {
      errorMsg.value = e.message
    }
  }

  /** 状态对账（SSE 不可用时退化为 3 秒轮询）。 */
  async function refreshStatus() {
    if (!taskNo.value) {
      return
    }
    try {
      const view = await fetchTaskStatus(taskNo.value)
      status.value = view.status
      progress.value = view.progress
      currentAgent.value = view.currentAgent || ''
      failedAgent.value = view.failedAgent || ''
      tokenTotal.value = view.tokenTotal
      durationMs.value = view.durationMs
      if (view.reportReady && !report.value) {
        await loadReport()
      }
    } catch (e) {
      errorMsg.value = e.message
    }
  }

  return {
    taskNo,
    status,
    progress,
    currentAgent,
    failedAgent,
    tokenTotal,
    durationMs,
    level,
    scorePreview,
    events,
    report,
    meta,
    errorMsg,
    submitting,
    connectionState,
    lastSeq,
    lastHeartbeat,
    reconnectCount,
    gapFilledCount,
    timeline,
    isRunning,
    isFinished,
    submit,
    reset,
    disconnect,
    reconnect,
    reconcile,
    refreshStatus,
    loadReport,
    closeStream
  }
}

function genTaskNo() {
  if (window.crypto && window.crypto.randomUUID) {
    return window.crypto.randomUUID().replace(/-/g, '')
  }
  return `task${Date.now()}${Math.floor(Math.random() * 1000)}`
}
