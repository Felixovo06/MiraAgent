export interface Message {
  id: string
  role: 'user' | 'assistant' | 'tool' | 'system'
  content: string | null
  toolCallId?: string
  toolName?: string
  toolCalls?: Array<{ id: string; name: string; arguments: string }>
  createdAt: string
}

export interface ToolExecution {
  toolCallId: string
  toolName: string
  status: 'SUCCESS' | 'ERROR' | 'DENIED'
  content: string
}

export interface ChatResponse {
  runId: string
  sessionId: string
  traceId?: string
  finalMessage?: {
    role: 'assistant'
    content: string | null
  }
  content: string | null
  status: string
  toolExecutions: ToolExecution[]
  usage?: { inputTokens: number; outputTokens: number }
  error?: string
}

export interface ToolCall {
  id: string
  name: string
  arguments: string
}

export type StreamEvent =
  | { type: 'start'; runId: string; sessionId: string }
  | { type: 'text_delta'; text: string }
  | { type: 'tool_call'; toolCall: ToolCall }
  | { type: 'tool_result'; toolResult: ToolExecution }
  | { type: 'trace'; trace: TraceEvent }
  | { type: 'done'; response: ChatResponse }
  | { type: 'error'; message: string }

export interface TraceEvent {
  id: string
  runId: string
  sessionId: string
  stepIndex: number
  eventType: string
  payload: Record<string, unknown>
  createdAt: string
}
