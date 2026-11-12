<template>
  <el-card shadow="never" class="replay-card">
    <template #header>
      <div class="card-head">
        <span class="section-title">离线重放（断网演示）</span>
        <div class="head-right">
          <el-tag v-if="replay" size="small" type="warning" effect="light">
            重放模式 · 倍速 {{ replay.replay_speed }}
          </el-tag>
          <el-button size="small" @click="refresh">刷新快照</el-button>
        </div>
      </div>
    </template>

    <p class="hint">
      重放全程不调用外部模型接口：加载快照后按录制的事件序列压缩节奏重发，断网状态下也能完整演示面试问答与总结。
    </p>

    <el-table :data="snapshots" size="small" empty-text="暂无快照，先在面试会话中录制" max-height="200">
      <el-table-column prop="snapshot_id" label="快照" width="90" />
      <el-table-column prop="kind" label="类型" width="90" />
      <el-table-column prop="round_count" label="轮次" width="70" />
      <el-table-column prop="event_count" label="事件数" width="80" />
      <el-table-column prop="status" label="状态" width="100" />
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button size="small" type="primary" :loading="loadingId === row.snapshot_id" @click="play(row)">
            加载重放
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="replay" class="replay-stage">
      <el-progress :percentage="percentage" :stroke-width="10" :show-text="false" />
      <div class="replay-meta">
        <span>已回放 {{ doneCount }} 个事件（共 {{ totalEvents }}）</span>
        <span v-if="lastTick">当前事件：{{ lastTick.event }} · 第 {{ lastTick.data.round_no || '-' }} 轮</span>
      </div>

      <div class="replay-transcript">
        <div v-for="item in bubbles" :key="item.key" class="bubble" :class="item.role">
          <div class="bubble-meta">{{ item.title }}</div>
          <div class="bubble-body">{{ item.text }}</div>
        </div>
      </div>

      <div v-if="summaryMd" class="replay-summary">
        <div class="summary-head">
          <span class="section-title">重放总结</span>
          <el-tag v-if="scoreInfo" size="small" type="success" effect="light">
            平均 {{ scoreInfo.avg_score }} 分 · {{ scoreInfo.answered_rounds || scoreInfo.planned_rounds || 0 }} 轮
          </el-tag>
        </div>
        <pre class="summary-text">{{ summaryMd }}</pre>
      </div>
    </div>

    <el-alert v-if="errorMsg" class="mt" type="error" :closable="false" show-icon :title="errorMsg" />
  </el-card>
</template>

<script setup>
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchInterview, interviewStreamUrl, listReplays, loadReplay } from '../api/interview'

const snapshots = ref([])
const replay = ref(null)
const events = ref([])
const bubbles = ref([])
const summaryMd = ref('')
const scoreInfo = ref(null)
const loadingId = ref('')
const errorMsg = ref('')
const replayFinished = ref(false)

let source = null

// 进度分母 = 快照事件数；控制事件（replay.tick / replay.finished）不计入回放进度
const totalEvents = computed(() => (replay.value ? replay.value.total_events || 0 : 0))
const progressEvents = computed(() => events.value.filter((e) => e.event !== 'replay.tick' && e.event !== 'replay.finished'))
const doneCount = computed(() => (replayFinished.value ? totalEvents.value : progressEvents.value.length))
const percentage = computed(() => {
  if (!replay.value) return 0
  const total = totalEvents.value
  if (!total) return 100
  return replayFinished.value ? 100 : Math.min(100, Math.round((progressEvents.value.length / total) * 100))
})
const lastTick = computed(() => {
  const ticks = events.value.filter((e) => e.event === 'replay.tick')
  return ticks.length ? ticks[ticks.length - 1] : null
})

async function refresh() {
  try {
    snapshots.value = await listReplays()
  } catch (e) {
    errorMsg.value = e.message || '快照列表获取失败'
  }
}

async function play(row) {
  if (loadingId.value) return
  loadingId.value = row.snapshot_id
  errorMsg.value = ''
  closeStream()
  events.value = []
  bubbles.value = []
  summaryMd.value = ''
  scoreInfo.value = null
  replayFinished.value = false
  try {
    const data = await loadReplay(row.snapshot_id)
    replay.value = data
    replay.value.total_events = row.event_count || 0
    connectStream(data)
    ElMessage.success(`已进入重放模式（快照 ${row.snapshot_id}）`)
  } catch (e) {
    errorMsg.value = e.message || '加载快照失败'
  } finally {
    loadingId.value = ''
  }
}

function connectStream(data) {
  source = new EventSource(data.stream)
  const names = ['interview.started', 'interview.question', 'interview.answer', 'interview.evaluation',
    'interview.progress', 'interview.finished', 'agent.started', 'agent.finished', 'agent.failed',
    'replay.tick', 'replay.finished']
  names.forEach((name) => {
    source.addEventListener(name, (ev) => {
      let payload = {}
      try { payload = JSON.parse(ev.data) } catch (e) { payload = {} }
      events.value.push({ seq: Number(ev.lastEventId) || 0, event: name, data: payload })
      handleEvent(name, payload)
    })
  })
  source.onerror = () => {
    // 服务器 complete 会触发 onerror：若快照末事件 interview.finished 已到达，视为回放完成并归位
    if (events.value.some((e) => e.event === 'interview.finished')) markReplayDone()
    closeStream()
  }
}

function handleEvent(name, payload) {
  if (name === 'interview.question') {
    bubbles.value.push({
      key: `q-${payload.round_no}-${events.value.length}`,
      role: 'interviewer',
      title: `第 ${payload.round_no} 轮 · 面试官`,
      text: payload.question || ''
    })
  }
  if (name === 'interview.answer') {
    bubbles.value.push({
      key: `a-${payload.round_no}-${events.value.length}`,
      role: 'candidate',
      title: `第 ${payload.round_no} 轮 · 候选人`,
      text: payload.answer || '（快照中仅记录回答长度）'
    })
  }
  if (name === 'interview.evaluation' && payload.score !== undefined) {
    bubbles.value.push({
      key: `e-${payload.round_no}-${events.value.length}`,
      role: 'evaluation',
      title: `第 ${payload.round_no} 轮评分`,
      text: `${payload.score} 分`
    })
  }
  if (name === 'interview.finished' || name === 'replay.finished') {
    markReplayDone()
  }
}

/** 回放完成：进度归位到 100%（后端已确认全部事件发布完成），并补齐总结。 */
function markReplayDone() {
  if (replayFinished.value) return
  replayFinished.value = true
  loadFinal()
}

async function loadFinal() {
  if (!replay.value) return
  try {
    const detail = await fetchInterview(replay.value.session_id)
    summaryMd.value = detail.summary_md || ''
    scoreInfo.value = detail.score || null
  } catch (e) {
    /* 忽略：重放结束后补齐失败不影响演示 */
  }
}

function closeStream() {
  if (source) {
    source.close()
    source = null
  }
}

onMounted(refresh)
onBeforeUnmount(closeStream)
</script>

<style scoped>
.replay-card {
  margin-bottom: 16px;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.head-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.section-title {
  font-weight: 600;
}

.hint {
  color: var(--op-text-secondary);
  font-size: 12px;
  line-height: 1.7;
  margin: 0 0 10px;
}

.replay-stage {
  margin-top: 14px;
}

.replay-meta {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: var(--op-text-secondary);
  margin: 6px 0 10px;
}

.replay-transcript {
  max-height: 240px;
  overflow-y: auto;
}

.bubble {
  border-radius: 8px;
  padding: 8px 10px;
  margin-bottom: 8px;
  font-size: 13px;
  line-height: 1.7;
  background: var(--op-bubble-candidate-bg);
  border: 1px solid var(--op-bubble-candidate-border);
}

.bubble.interviewer {
  background: var(--op-bubble-interviewer-bg);
  border-color: var(--op-bubble-interviewer-border);
}

.bubble.evaluation {
  background: var(--op-replay-eval-bg);
  border-color: var(--op-replay-eval-border);
  font-weight: 600;
  color: var(--op-replay-eval-color);
}

.bubble-meta {
  font-size: 12px;
  color: var(--op-text-secondary);
  margin-bottom: 4px;
}

.bubble-body {
  white-space: pre-wrap;
  color: var(--op-text-primary);
}

.replay-summary {
  margin-top: 12px;
  border-top: 1px dashed var(--op-border-light);
  padding-top: 10px;
}

.summary-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}

.summary-text {
  white-space: pre-wrap;
  font-family: inherit;
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--op-text-primary);
  margin: 0;
}

.mt {
  margin-top: 10px;
}
</style>
