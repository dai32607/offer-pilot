<template>
  <el-card shadow="never" class="timeline-card">
    <template #header>
      <div class="card-header">
        <span class="card-title">Agent 进度时间线</span>
        <div class="conn-state">
          <el-tag :type="connTagType" size="small" effect="plain">{{ connText }}</el-tag>
          <span class="heartbeat" v-if="lastHeartbeat">心跳 {{ lastHeartbeat }}</span>
        </div>
      </div>
    </template>

    <el-progress
      :percentage="progress"
      :status="progressStatus"
      :stroke-width="14"
      :text-inside="true"
      class="progress-bar"
    />

    <el-timeline class="timeline">
      <el-timeline-item
        v-for="node in timeline"
        :key="node.name"
        :type="nodeType(node.state)"
        :hollow="node.state === 'pending'"
        :timestamp="nodeTime(node)"
        placement="top"
      >
        <div class="node">
          <span class="node-label">{{ node.label }}</span>
          <el-tag size="small" :type="stateTagType(node.state)" effect="light">
            {{ stateText(node.state) }}
          </el-tag>
          <el-tag v-if="node.model" size="small" effect="plain" type="info">{{ node.model }}</el-tag>
          <el-tag v-if="node.retried" size="small" type="warning" effect="light">重试 {{ node.attempts }} 次</el-tag>
          <el-tag v-if="node.state === 'running' && node.elapsedMs" size="small" type="info" effect="light">
            已耗时 {{ (node.elapsedMs / 1000).toFixed(1) }}s
          </el-tag>
        </div>
        <div class="node-meta" v-if="node.latencyMs || node.tokens || node.errorCode">
          <span v-if="node.latencyMs">耗时 {{ (node.latencyMs / 1000).toFixed(1) }}s</span>
          <span v-if="node.tokens">token {{ node.tokens }}</span>
          <span v-if="node.errorCode" class="err">错误码 {{ node.errorCode }}</span>
        </div>
      </el-timeline-item>
    </el-timeline>

    <div class="counters">
      <span>已接收事件 {{ events.length }}</span>
      <span>最新 seq {{ lastSeq }}</span>
      <span>重连次数 {{ reconnectCount }}</span>
      <span>补齐事件 {{ gapFilledCount }}</span>
    </div>
  </el-card>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  timeline: { type: Array, default: () => [] },
  events: { type: Array, default: () => [] },
  progress: { type: Number, default: 0 },
  status: { type: String, default: '' },
  connectionState: { type: String, default: 'idle' },
  lastSeq: { type: Number, default: 0 },
  lastHeartbeat: { type: String, default: '' },
  reconnectCount: { type: Number, default: 0 },
  gapFilledCount: { type: Number, default: 0 }
})

const connText = computed(() => {
  const map = {
    idle: '未连接',
    connecting: '连接中',
    open: '已连接',
    reconnecting: '断线重连中',
    disconnected: '已断开',
    closed: '已结束'
  }
  return map[props.connectionState] || props.connectionState
})

const connTagType = computed(() => {
  if (props.connectionState === 'open') return 'success'
  if (props.connectionState === 'reconnecting' || props.connectionState === 'connecting') return 'warning'
  if (props.connectionState === 'disconnected') return 'danger'
  return 'info'
})

const progressStatus = computed(() => {
  if (props.status === 'FAILED') return 'exception'
  if (props.status === 'SUCCEEDED') return 'success'
  return undefined
})

function nodeType(state) {
  if (state === 'done') return 'success'
  if (state === 'running') return 'primary'
  if (state === 'failed') return 'danger'
  return 'info'
}

function stateTagType(state) {
  if (state === 'done') return 'success'
  if (state === 'running') return 'primary'
  if (state === 'failed') return 'danger'
  return 'info'
}

function stateText(state) {
  return { pending: '待执行', running: '进行中', done: '已完成', failed: '失败' }[state] || state
}

function nodeTime(node) {
  if (node.state === 'done' && node.latencyMs) return `用时 ${(node.latencyMs / 1000).toFixed(1)}s`
  if (node.state === 'running') return '执行中'
  return ''
}
</script>

<style scoped>
.timeline-card {
  margin-bottom: 16px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-title {
  font-weight: 600;
}

.conn-state {
  display: flex;
  align-items: center;
  gap: 8px;
}

.heartbeat {
  color: var(--op-text-secondary);
  font-size: 12px;
}

.progress-bar {
  margin-bottom: 20px;
}

.node {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.node-label {
  font-weight: 600;
  color: var(--op-text-primary);
}

.node-meta {
  margin-top: 4px;
  color: var(--op-text-secondary);
  font-size: 12px;
  display: flex;
  gap: 16px;
}

.node-meta .err {
  color: #f56c6c;
}

.counters {
  display: flex;
  gap: 18px;
  color: var(--op-text-secondary);
  font-size: 12px;
  border-top: 1px solid var(--op-border-light);
  padding-top: 10px;
}
</style>
