import type {
  CharacterCard,
  ChatResponse,
  CuratorReport,
  DocumentInfo,
  MemoryItem,
  Message,
  SkillDetail,
  SkillIndex,
  StreamEvent,
  ToolCall,
  ToolExecution,
  ToolInfo,
  TraceEvent,
  WeixinLoginState,
} from './types'

const BASE = '/api'

export async function sendChat(payload: {
  userId: string
  sessionId: string
  characterId?: string
  content: string
  enabledTools?: string[]
}): Promise<ChatResponse> {
  const res = await fetch(`${BASE}/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}

export function streamChat(
  payload: {
    userId: string
    sessionId: string
    characterId?: string
    content: string
    enabledTools?: string[]
    images?: string[]
  },
  onEvent: (event: StreamEvent) => void,
  onError: (err: string) => void,
): () => void {
  const ctrl = new AbortController()
  const url = `${BASE}/chat/stream`

  fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...payload, stream: true }),
    signal: ctrl.signal,
  })
    .then(async (res) => {
      if (!res.ok || !res.body) {
        onError(`HTTP ${res.status}`)
        return
      }
      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      // eventName 必须跨 read() 分块保持：SSE 一帧的 event: 行与 data: 行可能落在不同网络块，
      // 若每块重置会丢掉 event 名导致该帧（含 done）被丢弃、流卡死。
      let eventName = ''

      const handleLine = (raw: string) => {
        const line = raw.replace(/\r$/, '')
        if (line === '') {
          eventName = '' // 空行 = 一帧结束
          return
        }
        if (line.startsWith('event:')) {
          eventName = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
          handleStreamData(eventName, line.slice(5).trim(), onEvent, onError)
        }
      }

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        let idx
        while ((idx = buffer.indexOf('\n')) >= 0) {
          handleLine(buffer.slice(0, idx))
          buffer = buffer.slice(idx + 1)
        }
      }
      if (buffer) handleLine(buffer)
    })
    .catch((err) => {
      if (err.name !== 'AbortError') onError(String(err))
    })

  return () => ctrl.abort()
}

function handleStreamData(
  eventName: string,
  data: string,
  onEvent: (event: StreamEvent) => void,
  onError: (err: string) => void,
) {
  if (eventName === 'start') {
    const parsed = JSON.parse(data) as { runId: string; sessionId: string }
    onEvent({ type: 'start', ...parsed })
  } else if (eventName === 'text_delta') {
    onEvent({ type: 'text_delta', text: JSON.parse(data).text ?? '' })
  } else if (eventName === 'tool_call') {
    onEvent({ type: 'tool_call', toolCall: JSON.parse(data) as ToolCall })
  } else if (eventName === 'tool_result') {
    onEvent({ type: 'tool_result', toolResult: JSON.parse(data) as ToolExecution })
  } else if (eventName === 'trace') {
    onEvent({ type: 'trace', trace: JSON.parse(data) as TraceEvent })
  } else if (eventName === 'done') {
    onEvent({ type: 'done', response: JSON.parse(data) as ChatResponse })
  } else if (eventName === 'error') {
    const message = JSON.parse(data).message ?? 'Stream error'
    onEvent({ type: 'error', message })
    onError(message)
  }
}

export async function getMessages(sessionId: string): Promise<Message[]> {
  const res = await fetch(`${BASE}/sessions/${sessionId}/messages`)
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}

export async function getTrace(runId: string): Promise<TraceEvent[]> {
  const res = await fetch(`${BASE}/traces/${runId}`)
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}

export async function interrupt(runId: string): Promise<void> {
  await fetch(`${BASE}/runs/${runId}/interrupt`, { method: 'POST' })
}

// ---- Skills 管理 ----

export async function getSkills(): Promise<SkillIndex[]> {
  const res = await fetch(`${BASE}/skills`)
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}

export async function getSkill(skillId: string): Promise<SkillDetail> {
  const res = await fetch(`${BASE}/skills/${skillId}`)
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}

export async function pinSkill(skillId: string, pinned: boolean): Promise<void> {
  const res = await fetch(`${BASE}/skills/${skillId}/pin?pinned=${pinned}`, { method: 'POST' })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
}

export async function archiveSkill(skillId: string): Promise<void> {
  const res = await fetch(`${BASE}/skills/${skillId}/archive`, { method: 'POST' })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
}

export async function getCuratorReport(): Promise<CuratorReport> {
  const res = await fetch(`${BASE}/skills/curator-report`)
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}

// ---- 工具 / 角色 / 记忆 / 会话 trace ----

export async function getTools(): Promise<ToolInfo[]> {
  const res = await fetch(`${BASE}/tools`)
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}

export async function getCharacters(): Promise<CharacterCard[]> {
  const res = await fetch(`${BASE}/characters`)
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}

export async function getMemories(userId: string): Promise<MemoryItem[]> {
  const res = await fetch(`${BASE}/memory?userId=${encodeURIComponent(userId)}`)
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}

export async function deleteMemory(memoryId: string, userId: string): Promise<void> {
  const res = await fetch(`${BASE}/memory/${memoryId}?userId=${encodeURIComponent(userId)}`, {
    method: 'DELETE',
  })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
}

export async function getSessionTrace(sessionId: string): Promise<TraceEvent[]> {
  const res = await fetch(`${BASE}/traces/sessions/${sessionId}`)
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}

// ---- 文档工作区 ----

export async function getDocuments(): Promise<DocumentInfo[]> {
  const res = await fetch(`${BASE}/documents`)
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}

export async function uploadDocument(file: File): Promise<DocumentInfo> {
  const form = new FormData()
  form.append('file', file)
  const res = await fetch(`${BASE}/documents`, { method: 'POST', body: form })
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${await res.text()}`)
  return res.json()
}

export async function deleteDocument(name: string): Promise<void> {
  const res = await fetch(`${BASE}/documents/${encodeURIComponent(name)}`, { method: 'DELETE' })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
}

export function documentDownloadUrl(name: string): string {
  return `${BASE}/documents/${encodeURIComponent(name)}`
}

// ---- 微信扫码登录 ----

export async function getWeixinStatus(): Promise<WeixinLoginState> {
  const res = await fetch(`${BASE}/weixin/status`)
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}

export async function requestWeixinQr(): Promise<WeixinLoginState> {
  const res = await fetch(`${BASE}/weixin/qr`, { method: 'POST' })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}

export async function pollWeixin(): Promise<WeixinLoginState> {
  const res = await fetch(`${BASE}/weixin/poll`)
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}
