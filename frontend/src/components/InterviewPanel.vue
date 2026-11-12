<template>
  <el-card shadow="never" class="interview-card">
    <template #header>
      <div class="card-head">
        <span class="section-title">模拟面试</span>
        <div class="head-right">
          <el-tag v-if="session" size="small" type="info" effect="plain">session #{{ session.session_id }}</el-tag>
          <el-tag v-if="session" size="small" :type="statusType" effect="light">{{ statusText }}</el-tag>
        </div>
      </div>
    </template>

    <div v-if="!session" class="start-box">
      <p class="hint">
        基于当前任务的 JD、简历与匹配报告生成逐轮追问；每轮问题都会绑定一个考察维度并引用简历原文证据。
      </p>
      <div class="start-row">
        <span class="field-label">最大轮次</span>
        <el-input-number v-model="maxRound" :min="1" :max="5" size="small" />
        <span class="field-label">快照编号</span>
        <el-input v-model="caseId" size="small" placeholder="如 C01（可留空）" style="width: 140px" />
        <el-button type="primary" size="small" :loading="busy" :disabled="!ready" @click="open">
          开启模拟面试
        </el-button>
      </div>
      <el-alert v-if="!ready" type="info" :closable="false" show-icon
        title="需先完成匹配分析，面试问题依赖 JD、简历与匹配报告" />
    </div>

    <div v-else>
      <div class="transcript" ref="transcriptRef">
        <template v-for="turn in turns" :key="turn.round">
          <div class="bubble interviewer">
            <div class="bubble-meta">
              <span>第 {{ turn.round }} 轮 · 面试官</span>
              <el-tag size="small" effect="plain">{{ turn.focus_dimension || '综合' }}</el-tag>
            </div>
            <div class="bubble-body">{{ turn.question }}</div>
            <div v-if="turn.evidence_ref" class="evidence">证据引用：{{ turn.evidence_ref }}</div>
            <div v-if="turn.latency_ms" class="latency">生成耗时 {{ (turn.latency_ms / 1000).toFixed(1) }}s</div>
          </div>

          <div v-if="turn.answer" class="bubble candidate">
            <div class="bubble-meta"><span>第 {{ turn.round }} 轮 · 候选人</span></div>
            <div class="bubble-body">{{ turn.answer }}</div>
          </div>

          <div v-if="turn.score !== null && turn.score !== undefined" class="evaluation">
            <div class="eval-head">
              <span>本轮评分</span>
              <span class="score" :class="scoreClass(turn.score)">{{ turn.score }}</span>
            </div>
            <div class="eval-cols">
              <div>
                <div class="eval-title">做得好的地方</div>
                <ul><li v-for="(s, i) in turn.strengths || []" :key="i">{{ s }}</li></ul>
              </div>
              <div>
                <div class="eval-title">可改进的地方</div>
                <ul><li v-for="(s, i) in turn.improvements || []" :key="i">{{ s }}</li></ul>
              </div>
            </div>
            <div v-if="expertView && (turn.answer_points || []).length" class="answer-points">
              <div class="eval-title">参考回答要点（面试官视角）</div>
              <ul><li v-for="(s, i) in turn.answer_points" :key="i">{{ s }}</li></ul>
            </div>
          </div>
        </template>

        <div v-if="progressText && !waiting.active" class="progress-line">
          <span class="spinner"></span>
          <span>{{ progressText }}</span>
        </div>

        <div v-if="waiting.active" class="waiting-box">
          <div class="waiting-row">
            <span class="spinner-lg"></span>
            <div class="waiting-info">
              <div class="waiting-title">面试官正在评分…（第 {{ waiting.round }} 轮）</div>
              <div class="waiting-meta">
                已用时 {{ waiting.elapsed }}s
                <template v-if="!waiting.exceed"> · 预估剩余约 {{ waitRemain }}s</template>
                <template v-else> · 已超过预估时长，请耐心等待（后端超时上限 60s，超时会自动回滚）</template>
              </div>
            </div>
          </div>
          <el-progress :percentage="waitPercent" :stroke-width="8" :show-text="false" class="waiting-bar" />
        </div>
      </div>

      <div class="answer-box">
        <el-input v-model="draft" type="textarea" :rows="4" :disabled="finished || busy"
          placeholder="输入你的回答（可结合具体经历与数据）" />
        <div class="answer-actions">
          <el-button type="primary" :loading="busy" :disabled="finished || busy || !draft.trim()" @click="submitAnswer">
            提交回答
          </el-button>
          <el-button :disabled="finished || busy" @click="endInterview">结束面试并出总结</el-button>
          <el-switch v-model="expertView" active-text="面试官视角" inline-prompt
            :disabled="!hasAnswered" />
          <el-button size="small" :disabled="!session.case_id" @click="snapshot">录制快照</el-button>
        </div>
      </div>

      <el-alert v-if="errorMsg" class="mt" type="error" :closable="false" show-icon :title="errorMsg" />

      <div v-if="failedDraft" class="mt retry-row">
        <el-button type="warning" size="small" :loading="busy" @click="retryAnswer">
          重试提交刚才的回答
        </el-button>
        <span class="retry-hint">后端已自动回滚本轮未完成的评分，重试不会重复计分</span>
      </div>

      <div v-if="evalCards.length" class="events-strip">
        <span v-for="e in evalCards" :key="e.seq" class="strip-item">
          #{{ e.seq }} {{ e.event }}
          <template v-if="e.data && e.data.score !== undefined"> · {{ e.data.score }} 分</template>
        </span>
      </div>

      <el-divider />

      <div class="summary-view">
        <div class="summary-head">
          <span class="section-title">面试总结</span>
          <el-tag v-if="scoreInfo" size="small" type="success" effect="light">
            平均 {{ scoreInfo.avg_score }} 分 · 共 {{ scoreInfo.rounds }} 轮
          </el-tag>
        </div>
        <div v-if="!session.summary_md" class="empty">结束面试后生成总结与改进建议</div>
        <div v-else class="markdown" v-html="summaryHtml"></div>
        <div v-if="scoreInfo && (scoreInfo.dimension_avg || []).length" class="dim-table">
          <el-table :data="scoreInfo.dimension_avg" size="small" empty-text="暂无维度评分">
            <el-table-column prop="focus_dimension" label="考察维度" min-width="180" />
            <el-table-column prop="avg_score" label="平均分" width="100" />
            <el-table-column prop="rounds" label="轮次" width="80" />
          </el-table>
        </div>
      </div>
    </div>
  </el-card>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  fetchInterview,
  fetchInterviewEvents,
  finishInterview,
  interviewStreamUrl,
  recordInterviewSnapshot,
  sendAnswer,
  startInterview
} from '../api/interview'

const props = defineProps({
  taskNo: { type: String, default: '' },
  ready: { type: Boolean, default: false }
})

const session = ref(null)
const turns = ref([])
const events = ref([])
const draft = ref('')
const busy = ref(false)
const finished = ref(false)
const errorMsg = ref('')
const progressText = ref('')
const maxRound = ref(3)
const caseId = ref('')
const expertView = ref(false)
const transcriptRef = ref(null)
const failedDraft = ref('')

// ---------- 评分等待状态（配合后端 60s 超时回滚，可安全重试） ----------
const waiting = reactive({ active: false, round: 0, startedAt: 0, elapsed: 0, est: 90, exceed: false })
let waitTimer = null

const waitPercent = computed(() => {
  if (!waiting.active) return 0
  return Math.min(100, Math.round((waiting.elapsed / waiting.est) * 100))
})
const waitRemain = computed(() => Math.max(0, waiting.est - waiting.elapsed))

/** 以历史已生成轮次的 latency 均值预估本轮评分耗时（夹在 60-160s 区间，对应后端超时边界）。 */
function estimateRoundSec() {
  const lat = turns.value.filter((t) => t.latency_ms).map((t) => t.latency_ms / 1000)
  if (!lat.length) return 90
  const avg = lat.reduce((a, b) => a + b, 0) / lat.length
  return Math.min(160, Math.max(60, Math.round(avg)))
}

function startWaiting(round) {
  waiting.active = true
  waiting.round = round
  waiting.startedAt = Date.now()
  waiting.est = estimateRoundSec()
  waiting.elapsed = 0
  waiting.exceed = false
  stopWaitTimer()
  waitTimer = setInterval(tickWaiting, 1000)
}

function tickWaiting() {
  if (!waiting.active) return
  waiting.elapsed = Math.floor((Date.now() - waiting.startedAt) / 1000)
  waiting.exceed = waiting.elapsed > waiting.est
}

function stopWaiting() {
  waiting.active = false
  stopWaitTimer()
}

function stopWaitTimer() {
  if (waitTimer) {
    clearInterval(waitTimer)
    waitTimer = null
  }
}

let source = null

const statusText = computed(() => {
  const s = session.value && session.value.status
  return { ONGOING: '进行中', FINISHED: '已结束', ABORTED: '已中断' }[s] || (s || '-')
})
const statusType = computed(() => (session.value && session.value.status === 'FINISHED' ? 'success' : 'primary'))
const hasAnswered = computed(() => turns.value.some((t) => t.answer))
const evalCards = computed(() => events.value.filter((e) => e.event === 'interview.evaluation').slice(-4))
const scoreInfo = computed(() => {
  const score = session.value && session.value.score
  if (!score) return null
  return {
    avg_score: score.avg_score,
    rounds: score.answered_rounds || score.planned_rounds || 0,
    dimension_avg: (score.dimensions || []).map((d) => ({
      focus_dimension: d.name,
      avg_score: d.score,
      rounds: d.round_no
    }))
  }
})

const summaryHtml = computed(() => renderMarkdown(session.value && session.value.summary_md))

function renderMarkdown(md) {
  if (!md) return ''
  const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  const inline = (s) => esc(s).replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>').replace(/`(.+?)`/g, '<code>$1</code>')
  const lines = md.split(/\r?\n/)
  const out = []
  let inList = false
  for (const raw of lines) {
    const line = raw.trim()
    if (/^[-*+]\s+/.test(line)) {
      if (!inList) { out.push('<ul>'); inList = true }
      out.push(`<li>${inline(line.replace(/^[-*+]\s+/, ''))}</li>`)
      continue
    }
    if (inList) { out.push('</ul>'); inList = false }
    if (!line) continue
    const heading = line.match(/^(#{1,4})\s+(.*)$/)
    if (heading) {
      out.push(`<h4>${inline(heading[2])}</h4>`)
    } else {
      out.push(`<p>${inline(line)}</p>`)
    }
  }
  if (inList) out.push('</ul>')
  return out.join('')
}

function applySession(data) {
  session.value = data
  turns.value = data.transcript || []
  finished.value = data.status === 'FINISHED' || data.status === 'ABORTED'
  if (data.case_id) caseId.value = data.case_id
}

function connectStream() {
  if (!session.value) return
  closeStream()
  source = new EventSource(interviewStreamUrl(session.value.session_id))
  const names = ['interview.started', 'interview.question', 'interview.answer', 'interview.evaluation',
    'interview.progress', 'interview.finished', 'agent.started', 'agent.finished', 'agent.failed', 'replay.tick']
  names.forEach((name) => {
    source.addEventListener(name, (ev) => {
      let data = {}
      try { data = JSON.parse(ev.data) } catch (e) { data = {} }
      events.value.push({ seq: Number(ev.lastEventId) || 0, event: name, data })
      if (name === 'interview.progress') {
        progressText.value = `面试官思考中…（第 ${data.round_no} 轮，已耗时 ${(data.elapsed_ms / 1000).toFixed(1)}s）`
        if (!waiting.active) {
          startWaiting(data.round_no || (turns.value.length + 1))
        }
        if (data.elapsed_ms) {
          waiting.elapsed = Math.max(waiting.elapsed, Math.floor(data.elapsed_ms / 1000))
        }
      }
      if (name === 'interview.question' || name === 'interview.evaluation') {
        progressText.value = ''
        stopWaiting()
      }
      if (name === 'interview.finished') {
        finished.value = true
        progressText.value = ''
        stopWaiting()
        refresh()
      }
    })
  })
  source.onerror = () => { /* 结束或断线：由下一轮操作自动重连 */ }
}

function closeStream() {
  if (source) {
    source.close()
    source = null
  }
}

async function open() {
  if (!props.taskNo || !props.ready) return
  busy.value = true
  errorMsg.value = ''
  try {
    const data = await startInterview(props.taskNo, {
      max_round: maxRound.value,
      case_id: caseId.value ? caseId.value.trim() : undefined
    })
    session.value = { session_id: data.session_id, status: data.status, max_round: data.max_round, case_id: data.case_id }
    turns.value = [{
      round: 1,
      question: data.question,
      focus_dimension: data.focus_dimension,
      evidence_ref: data.evidence_ref
    }]
    finished.value = false
    connectStream()
    ElMessage.success('面试已开始，请回答首轮问题')
  } catch (e) {
    errorMsg.value = e.message || '开启面试失败'
  } finally {
    busy.value = false
  }
}

async function submitAnswer() {
  if (!session.value || !draft.value.trim()) return
  const answerText = draft.value.trim()
  const roundNo = (turns.value.length || 0) + 1
  busy.value = true
  errorMsg.value = ''
  failedDraft.value = ''
  startWaiting(roundNo)
  try {
    const data = await sendAnswer(session.value.session_id, answerText)
    stopWaiting()
    await refresh()
    draft.value = ''
    if (data.status === 'FINISHED') {
      finished.value = true
      ElMessage.success('面试已结束，总结已生成')
    } else {
      ElMessage.success(`第 ${data.round_no} 轮评分已出，进入第 ${data.round_no + 1} 轮`)
    }
  } catch (e) {
    // 后端 60s 超时会回滚本轮 answer（setAnswer(null)），重试即重新提交同内容，不会重复计分
    stopWaiting()
    failedDraft.value = answerText
    errorMsg.value = e.message || '提交回答失败，请重试'
  } finally {
    busy.value = false
  }
}

/** 超时/失败后的重试入口：复用刚才的回答文本重新提交。 */
function retryAnswer() {
  if (!failedDraft.value) return
  draft.value = failedDraft.value
  failedDraft.value = ''
  errorMsg.value = ''
  submitAnswer()
}

async function endInterview() {
  if (!session.value) return
  busy.value = true
  errorMsg.value = ''
  try {
    await finishInterview(session.value.session_id)
    await refresh()
    finished.value = true
    ElMessage.success('面试总结已生成')
  } catch (e) {
    errorMsg.value = e.message || '结束面试失败'
  } finally {
    busy.value = false
  }
}

async function snapshot() {
  if (!session.value) return
  busy.value = true
  try {
    const data = await recordInterviewSnapshot(session.value.session_id, caseId.value || undefined)
    ElMessage.success(`已录制快照 ${data.snapshot_id}（${data.round_count} 轮 / ${data.event_count} 事件）`)
  } catch (e) {
    errorMsg.value = e.message || '录制快照失败'
  } finally {
    busy.value = false
  }
}

async function refresh() {
  if (!session.value) return
  try {
    const data = await fetchInterview(session.value.session_id)
    applySession(data)
    const history = await fetchInterviewEvents(session.value.session_id, 0)
    const known = new Set(events.value.map((e) => `${e.seq}`))
    history.forEach((h) => {
      if (!known.has(`${h.seq}`)) {
        events.value.push({ seq: h.seq, event: h.event, data: h.data })
      }
    })
    await nextTick()
    if (transcriptRef.value) {
      transcriptRef.value.scrollTop = transcriptRef.value.scrollHeight
    }
  } catch (e) {
    errorMsg.value = e.message || '会话刷新失败'
  }
}

watch(() => props.taskNo, () => {
  closeStream()
  session.value = null
  turns.value = []
  events.value = []
  finished.value = false
})

onBeforeUnmount(() => {
  closeStream()
  stopWaitTimer()
})

function scoreClass(score) {
  if (score >= 80) return 'high'
  if (score >= 60) return 'mid'
  return 'low'
}
</script>

<style scoped>
.interview-card {
  margin-bottom: 16px;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.head-right {
  display: flex;
  gap: 6px;
}

.section-title {
  font-weight: 600;
}

.start-box .hint {
  color: var(--op-text-secondary);
  font-size: 12px;
  line-height: 1.7;
  margin: 0 0 10px;
}

.start-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.field-label {
  font-size: 13px;
  color: var(--op-text-regular);
}

.transcript {
  max-height: 420px;
  overflow-y: auto;
  padding-right: 6px;
}

.bubble {
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 10px;
  font-size: 13px;
  line-height: 1.7;
}

.bubble.interviewer {
  background: var(--op-bubble-interviewer-bg);
  border: 1px solid var(--op-bubble-interviewer-border);
}

.bubble.candidate {
  background: var(--op-bubble-candidate-bg);
  border: 1px solid var(--op-bubble-candidate-border);
  margin-left: 28px;
}

.bubble-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--op-text-secondary);
  margin-bottom: 6px;
}

.bubble-body {
  white-space: pre-wrap;
  color: var(--op-text-primary);
}

.evidence,
.latency {
  margin-top: 6px;
  font-size: 12px;
  color: var(--op-text-secondary);
}

.evaluation {
  background: var(--op-eval-bg);
  border: 1px dashed var(--op-border-base);
  border-radius: 8px;
  padding: 10px 12px;
  margin: 0 0 12px 28px;
}

.eval-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 6px;
}

.score {
  font-size: 18px;
}

.score.high { color: #67c23a; }
.score.mid { color: #e6a23c; }
.score.low { color: #f56c6c; }

.eval-cols {
  display: flex;
  gap: 16px;
  font-size: 12px;
}

.eval-cols > div {
  flex: 1;
}

.eval-title {
  color: var(--op-text-regular);
  font-weight: 600;
  margin-bottom: 4px;
}

.evaluation ul,
.answer-points ul {
  margin: 0;
  padding-left: 18px;
  color: var(--op-text-regular);
  line-height: 1.7;
}

.answer-points {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed var(--op-border-light);
  font-size: 12px;
}

.progress-line {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--op-text-secondary);
  margin-bottom: 10px;
}

.spinner {
  width: 10px;
  height: 10px;
  border: 2px solid var(--op-border-base);
  border-top-color: #409eff;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* ---------- 评分等待（加载动画 + 进度条 + 预估剩余 + 已用时） ---------- */
.waiting-box {
  background: var(--op-eval-bg);
  border: 1px dashed var(--op-border-base);
  border-radius: 8px;
  padding: 12px 14px;
  margin: 0 0 12px 28px;
}

.waiting-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.spinner-lg {
  width: 18px;
  height: 18px;
  flex: none;
  border: 3px solid var(--op-border-base);
  border-top-color: var(--el-color-primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

.waiting-info {
  flex: 1;
}

.waiting-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--op-text-primary);
}

.waiting-meta {
  font-size: 12px;
  color: var(--op-text-secondary);
  margin-top: 2px;
}

.waiting-bar {
  margin-top: 10px;
}

.answer-box {
  margin-top: 10px;
}

.answer-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  flex-wrap: wrap;
}

.mt {
  margin-top: 10px;
}

.retry-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.retry-hint {
  font-size: 12px;
  color: var(--op-text-secondary);
}

.events-strip {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.strip-item {
  font-size: 12px;
  color: var(--op-text-secondary);
  background: var(--op-strip-bg);
  border-radius: 4px;
  padding: 2px 8px;
}

.summary-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.summary-view .empty {
  color: var(--op-text-secondary);
  font-size: 12px;
}

.markdown {
  font-size: 13px;
  line-height: 1.8;
  color: var(--op-text-primary);
}

.markdown :deep(h4) {
  margin: 10px 0 6px;
  font-size: 14px;
}

.markdown :deep(p) {
  margin: 6px 0;
}

.dim-table {
  margin-top: 10px;
}
</style>
