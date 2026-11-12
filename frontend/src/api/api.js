const BASE = '/api/v1'

async function postJson(path, payload) {
  const res = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
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

/** 创建分析任务：返回 taskNo 与 stream / report / events 入口。 */
export function createTask(payload) {
  return postJson(`${BASE}/tasks`, payload)
}

/** 任务状态（降级轮询与断线对账使用）。 */
export function fetchTaskStatus(taskNo) {
  return getJson(`${BASE}/tasks/${taskNo}`)
}

/** 最终报告。 */
export function fetchReport(taskNo) {
  return getJson(`${BASE}/tasks/${taskNo}/report`)
}

/** 事件历史（seq > after），供断线后 REST 补齐与逐 seq 对账。 */
export function fetchEvents(taskNo, after = 0) {
  return getJson(`${BASE}/tasks/${taskNo}/events?after=${after}`)
}

/** SSE 事件流地址。 */
export function streamUrl(taskNo) {
  return `${BASE}/tasks/${taskNo}/stream`
}
