import type { ChatResponse, Message, StreamEvent, ToolCall, ToolExecution, TraceEvent } from './types'

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
    content: string
    enabledTools?: string[]
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

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() ?? ''

        let eventName = ''
        for (const line of lines) {
          if (line.startsWith('event:')) {
            eventName = line.slice(6).trim()
          } else if (line.startsWith('data:')) {
            const data = line.slice(5).trim()
            handleStreamData(eventName, data, onEvent, onError)
          }
        }
      }
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
