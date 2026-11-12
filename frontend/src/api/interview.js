const BASE = '/api/v1'

async function postJson(path, payload) {
  const res = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload || {})
  })
  return unwrap(await res.json())
}

async function getJson(path) {
  const res = await fetch(path)
  return unwrap(await res.json())
}

function unwrap(body) {
  if (!body || typeof body.code === 'undefined') {
    throw new Error('响应格式异常')
  }
  if (body.code !== 0) {
    const err = new Error(body.message || `请求失败（code=${body.code}）`)
    err.code = body.code
    throw err
  }
  return body.data
}

/** A10 开启模拟面试：返回 session_id 与首轮 question。 */
export function startInterview(taskNo, payload = {}) {
  return postJson(`${BASE}/tasks/${taskNo}/interviews`, payload)
}

/** A11 提交回答：返回上一轮 evaluation、下一轮 question 与 round_no。 */
export function sendAnswer(sessionId, answer) {
  return postJson(`${BASE}/interviews/${sessionId}/messages`, { answer })
}

/** A12 面试会话详情：transcript / score / status。 */
export function fetchInterview(sessionId) {
  return getJson(`${BASE}/interviews/${sessionId}`)
}

/** A13 结束面试并出总结：summary_md + score。 */
export function finishInterview(sessionId) {
  return postJson(`${BASE}/interviews/${sessionId}/finish`, {})
}

/** 把会话录制为离线演示快照（断网重放用）。 */
export function recordInterviewSnapshot(sessionId, caseId) {
  return postJson(`${BASE}/interviews/${sessionId}/snapshot`, { case_id: caseId })
}

/** A14 演示快照列表。 */
export function listReplays() {
  return getJson(`${BASE}/replays`)
}

/** A15 加载快照进入离线重放模式。 */
export function loadReplay(snapshotId) {
  return postJson(`${BASE}/replays/${snapshotId}/load`, {})
}

/** 面试事件历史（seq > after），供断线后 REST 补齐。 */
export function fetchInterviewEvents(sessionId, after = 0) {
  return getJson(`${BASE}/interviews/${sessionId}/events?after=${after}`)
}

/** 面试 SSE 事件流地址。 */
export function interviewStreamUrl(sessionId) {
  return `${BASE}/interviews/${sessionId}/stream`
}
