<template>
  <div v-if="report" class="report">
    <el-card shadow="never" class="score-card">
      <div class="score-row">
        <div class="score-block">
          <div class="score-value">{{ report.overallScore }}</div>
          <div class="score-label">总分（满分 100）</div>
        </div>
        <div class="score-block">
          <el-tag :type="levelTagType" size="large" effect="dark">{{ report.level }}</el-tag>
          <div class="score-label">匹配等级</div>
        </div>
        <div class="score-block">
          <div class="score-value small">{{ report.dimensions.length }}</div>
          <div class="score-label">评维度数</div>
        </div>
        <div class="score-block">
          <div class="score-value small">{{ report.uncertaintyFlags.length }}</div>
          <div class="score-label">不确定维度</div>
        </div>
      </div>

      <el-alert
        v-if="report.uncertaintyFlags.length"
        type="warning"
        :closable="false"
        show-icon
        class="flag-alert"
        :title="`不确定维度：${report.uncertaintyFlags.join('、')}`"
        description="以下维度缺少可定位的原文证据或模型明确标记信息不足，其得分与结论仅供参考。"
      />

      <div class="notes" v-if="report.notes && report.notes.length">
        <p v-for="(n, i) in report.notes" :key="i" class="note">· {{ n }}</p>
      </div>
    </el-card>

    <el-card shadow="never" class="section-card">
      <template #header><span class="section-title">五维度评分与权重</span></template>
      <div v-for="dim in report.dimensions" :key="dim.name" class="dim">
        <div class="dim-head">
          <span class="dim-name">{{ dim.name }}</span>
          <el-tag size="small" effect="plain" type="info">权重 {{ dim.weight }}</el-tag>
          <el-tag size="small" effect="plain">得分 {{ dim.score }} / {{ dim.maxScore }}</el-tag>
          <el-tag v-if="dim.uncertainty" size="small" type="warning" effect="light">信息不足</el-tag>
        </div>
        <el-progress
          :percentage="percent(dim)"
          :stroke-width="12"
          :color="barColor(dim)"
          :show-text="false"
        />
        <p class="dim-conclusion">{{ dim.conclusion }}</p>
        <div class="evidence" v-if="dim.evidence && dim.evidence.length">
          <div v-for="ev in dim.evidence" :key="ev.evidenceId" class="evidence-item">
            <el-tag size="small" :type="ev.source === 'resume' ? 'success' : 'primary'" effect="plain">
              {{ ev.source === 'resume' ? '简历原文' : 'JD 原文' }}
            </el-tag>
            <span class="evidence-id">{{ ev.evidenceId }}</span>
            <span class="quote">「{{ ev.quote }}」</span>
          </div>
        </div>
        <p class="no-evidence" v-else>该维度无可定位的原文证据</p>
      </div>
    </el-card>

    <el-card shadow="never" class="section-card">
      <template #header><span class="section-title">命中项（{{ report.hits.length }}）</span></template>
      <el-table :data="report.hits" size="small" empty-text="无命中项">
        <el-table-column prop="requirement" label="JD 要求" min-width="180" />
        <el-table-column prop="resumeQuote" label="简历原文证据" min-width="220" />
        <el-table-column prop="comment" label="说明" min-width="140" />
      </el-table>
    </el-card>

    <el-card shadow="never" class="section-card">
      <template #header><span class="section-title">缺口项（{{ report.gaps.length }}）</span></template>
      <el-table :data="report.gaps" size="small" empty-text="无缺口项">
        <el-table-column prop="requirement" label="未满足要求" min-width="160" />
        <el-table-column prop="gapType" label="缺失类型" width="120" />
        <el-table-column label="严重程度" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="severityType(row.severity)" effect="light">{{ row.severity }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="suggestion" label="补齐建议" min-width="200" />
      </el-table>
    </el-card>

    <el-card shadow="never" class="section-card">
      <template #header><span class="section-title">风险项（{{ report.risks.length }}）</span></template>
      <div v-if="report.risks.length" class="risks">
        <div v-for="(r, i) in report.risks" :key="i" class="risk">
          <div class="risk-head">
            <span class="risk-title">{{ r.risk }}</span>
            <el-tag v-if="r.evidenceRef" size="small" effect="plain">证据 {{ r.evidenceRef }}</el-tag>
            <el-tag v-else size="small" type="info" effect="plain">无证据引用</el-tag>
          </div>
          <p class="risk-reason">{{ r.reason }}</p>
        </div>
      </div>
      <el-empty v-else description="无风险项" :image-size="60" />
    </el-card>

    <el-card shadow="never" class="section-card">
      <template #header>
        <span class="section-title">证据索引（{{ report.evidenceIndex.length }}）</span>
      </template>
      <el-table :data="report.evidenceIndex" size="small" max-height="280" empty-text="无证据">
        <el-table-column prop="evidenceId" label="编号" width="90" />
        <el-table-column label="来源" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.source === 'resume' ? 'success' : 'primary'" effect="plain">
              {{ row.source === 'resume' ? '简历' : 'JD' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="quote" label="原文片段" min-width="320" />
      </el-table>
    </el-card>

    <el-card shadow="never" class="section-card" v-if="meta">
      <template #header><span class="section-title">执行元数据</span></template>
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="解析模型">{{ meta.jdParse?.model }}</el-descriptions-item>
        <el-descriptions-item label="评估模型">{{ meta.matchModel }}</el-descriptions-item>
        <el-descriptions-item label="JD 解析耗时">
          {{ ((meta.jdParse?.elapsedMs || 0) / 1000).toFixed(1) }}s
        </el-descriptions-item>
        <el-descriptions-item label="简历解析耗时">
          {{ ((meta.resumeParse?.elapsedMs || 0) / 1000).toFixed(1) }}s
        </el-descriptions-item>
        <el-descriptions-item label="评估耗时">
          {{ ((meta.matchEval?.elapsedMs || 0) / 1000).toFixed(1) }}s
        </el-descriptions-item>
        <el-descriptions-item label="评估重试">{{ meta.matchEval?.retried ? `是（${meta.matchEval?.attempts} 次调用）` : '否' }}</el-descriptions-item>
        <el-descriptions-item label="Token 合计">
          {{ (meta.jdParse?.totalTokens || 0) + (meta.resumeParse?.totalTokens || 0) + (meta.matchEval?.totalTokens || 0) }}
        </el-descriptions-item>
        <el-descriptions-item label="权重重归一化">{{ meta.renormalized ? '是' : '否' }}</el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>

  <el-card v-else-if="loading" shadow="never" class="skeleton-card">
    <div class="skeleton-head">
      <div class="sk-line w40"></div>
      <div class="sk-line w18"></div>
    </div>

    <div class="skeleton-score-row">
      <div v-for="i in 4" :key="i" class="sk-block">
        <div class="sk-bar h34 w48"></div>
        <div class="sk-line w52"></div>
      </div>
    </div>

    <div class="skeleton-loading">
      <span class="spinner-lg"></span>
      <div class="skeleton-loading-info">
        <div class="skeleton-loading-title">报告生成中…（预计 20~160 秒）</div>
        <div class="skeleton-loading-meta">正在解析 JD 与简历，并进行五维度匹配评估，请稍候</div>
      </div>
    </div>

    <div v-for="i in 3" :key="i" class="skeleton-section">
      <div class="sk-line w30"></div>
      <div v-for="j in 2" :key="j" class="skeleton-progress-row">
        <div class="sk-line w16"></div>
        <div class="sk-bar h12 grow"></div>
      </div>
    </div>

    <div class="skeleton-table">
      <div v-for="i in 3" :key="i" class="skeleton-table-row">
        <div class="sk-line w22"></div>
        <div class="sk-line w34"></div>
        <div class="sk-line w26"></div>
      </div>
    </div>
  </el-card>

  <el-card v-else shadow="never" class="placeholder-card">
    <el-empty description="提交任务后，报告将在此生成" />
  </el-card>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  report: { type: Object, default: null },
  meta: { type: Object, default: null },
  loading: { type: Boolean, default: false }
})

const levelTagType = computed(() => {
  const level = props.report?.level
  if (level === '高度匹配') return 'success'
  if (level === '较匹配') return 'primary'
  if (level === '一般') return 'warning'
  return 'danger'
})

function percent(dim) {
  if (!dim.maxScore) return 0
  return Math.round((dim.score / dim.maxScore) * 100)
}

function barColor(dim) {
  if (dim.uncertainty) return '#e6a23c'
  const p = percent(dim)
  if (p >= 80) return '#67c23a'
  if (p >= 60) return '#409eff'
  if (p >= 40) return '#e6a23c'
  return '#f56c6c'
}

function severityType(severity) {
  if (severity === '高') return 'danger'
  if (severity === '中') return 'warning'
  return 'info'
}
</script>

<style scoped>
.report {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.score-row {
  display: flex;
  align-items: center;
  gap: 48px;
}

.score-block {
  text-align: center;
}

.score-value {
  font-size: 44px;
  font-weight: 700;
  color: var(--el-color-primary);
  line-height: 1.2;
}

.score-value.small {
  font-size: 30px;
  color: var(--op-text-regular);
}

.score-label {
  color: var(--op-text-secondary);
  font-size: 13px;
  margin-top: 4px;
}

.flag-alert {
  margin-top: 16px;
}

.notes {
  margin-top: 12px;
}

.note {
  color: var(--op-text-secondary);
  font-size: 13px;
  margin: 4px 0;
}

.section-title {
  font-weight: 600;
}

.dim {
  padding: 12px 0;
  border-bottom: 1px dashed var(--op-border-dashed);
}

.dim:last-child {
  border-bottom: none;
}

.dim-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.dim-name {
  font-weight: 600;
}

.dim-conclusion {
  color: var(--op-text-regular);
  font-size: 13px;
  margin: 8px 0 6px;
}

.evidence {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.evidence-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.evidence-id {
  color: var(--op-text-secondary);
}

.quote {
  color: var(--op-text-primary);
}

.no-evidence {
  color: #f56c6c;
  font-size: 13px;
  margin: 4px 0 0;
}

.risks {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.risk-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.risk-title {
  font-weight: 600;
}

.risk-reason {
  color: var(--op-text-regular);
  font-size: 13px;
  margin: 4px 0 0;
}

.placeholder-card {
  margin-bottom: 16px;
}

/* ---------- 报告生成骨架屏（与面试等待 UI 风格统一，暗色兼容） ---------- */
.skeleton-card {
  margin-bottom: 16px;
}

.skeleton-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 18px;
}

.skeleton-score-row {
  display: flex;
  align-items: center;
  justify-content: space-around;
  gap: 32px;
  padding: 10px 0 18px;
  border-bottom: 1px dashed var(--op-border-dashed);
}

.sk-block {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.skeleton-loading {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 18px 0;
  background: var(--op-eval-bg);
  border: 1px dashed var(--op-border-base);
  border-radius: 8px;
  padding: 12px 14px;
}

.spinner-lg {
  width: 18px;
  height: 18px;
  flex: none;
  border: 3px solid var(--op-border-base);
  border-top-color: var(--el-color-primary);
  border-radius: 50%;
  animation: skeleton-spin 0.8s linear infinite;
}

@keyframes skeleton-spin {
  to { transform: rotate(360deg); }
}

.skeleton-loading-info {
  flex: 1;
}

.skeleton-loading-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--op-text-primary);
}

.skeleton-loading-meta {
  font-size: 12px;
  color: var(--op-text-secondary);
  margin-top: 2px;
}

.skeleton-section {
  padding: 14px 0;
  border-bottom: 1px dashed var(--op-border-dashed);
}

.skeleton-progress-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 10px;
}

.skeleton-table {
  padding-top: 14px;
}

.skeleton-table-row {
  display: flex;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid var(--op-border-light);
}

/* 骨架条：shimmer 流动动画，亮/暗色均使用 CSS 变量 */
.sk-line,
.sk-bar {
  background: linear-gradient(
    90deg,
    var(--op-strip-bg) 25%,
    var(--op-border-light) 37%,
    var(--op-strip-bg) 63%
  );
  background-size: 400% 100%;
  animation: skeleton-shimmer 1.4s ease infinite;
  border-radius: 4px;
}

@keyframes skeleton-shimmer {
  0% { background-position: 100% 50%; }
  100% { background-position: 0 50%; }
}

.sk-line { height: 12px; }
.sk-bar { height: 16px; }
.sk-bar.h12 { height: 12px; }
.sk-bar.h34 { height: 34px; }
.sk-line.w15 { width: 15%; }
.sk-line.w16 { width: 16%; }
.sk-line.w18 { width: 18%; }
.sk-line.w20 { width: 20%; }
.sk-line.w22 { width: 22%; }
.sk-line.w26 { width: 26%; }
.sk-line.w30 { width: 30%; }
.sk-line.w34 { width: 34%; }
.sk-line.w40 { width: 40%; }
.sk-line.w48 { width: 48%; }
.sk-line.w50 { width: 50%; }
.sk-line.w52 { width: 52%; }
.sk-bar.w48 { width: 48%; }
.sk-bar.grow { flex: 1; }
</style>
