import { useCallback, useEffect, useRef, useState } from 'react'
import { streamChat, interrupt } from '../api'
import type { ChatResponse, ToolExecution } from '../types'
import { MessageBubble } from './MessageBubble'
import { ToolEventArea } from './ToolEventArea'
import './Chat.css'

interface LocalMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  pending?: boolean
}

interface Props {
  onRunComplete: (runId: string) => void
}

const SESSION_KEY = 'mira_session_id'

function getOrCreateSessionId(): string {
  let id = sessionStorage.getItem(SESSION_KEY)
  if (!id) {
    id = crypto.randomUUID()
    sessionStorage.setItem(SESSION_KEY, id)
  }
  return id
}

export function Chat({ onRunComplete }: Props) {
  const [messages, setMessages] = useState<LocalMessage[]>([])
  const [input, setInput] = useState('')
  const [running, setRunning] = useState(false)
  const [toolExecutions, setToolExecutions] = useState<ToolExecution[]>([])
  const [activeRunId, setActiveRunId] = useState<string | null>(null)
  const abortRef = useRef<(() => void) | null>(null)
  const bottomRef = useRef<HTMLDivElement>(null)
  const sessionId = useRef(getOrCreateSessionId())

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const handleSend = useCallback(() => {
    const content = input.trim()
    if (!content || running) return

    const userMsgId = crypto.randomUUID()
    const pendingId = crypto.randomUUID()

    setMessages((prev) => [
      ...prev,
      { id: userMsgId, role: 'user', content },
      { id: pendingId, role: 'assistant', content: '', pending: true },
    ])
    setInput('')
    setRunning(true)
    setToolExecutions([])

    const abort = streamChat(
      { userId: 'user-1', sessionId: sessionId.current, content },
      (event) => {
        if (event.type === 'start') {
          setActiveRunId(event.runId)
          onRunComplete(event.runId)
        } else if (event.type === 'text_delta') {
          setMessages((prev) =>
            prev.map((m) =>
              m.id === pendingId
                ? { ...m, content: `${m.content}${event.text}` }
                : m,
            ),
          )
        } else if (event.type === 'tool_result') {
          setToolExecutions((prev) => [...prev, event.toolResult])
        } else if (event.type === 'done') {
          const response: ChatResponse = event.response
          const finalContent = response.finalMessage?.content ?? response.content ?? ''
          setMessages((prev) =>
            prev.map((m) =>
              m.id === pendingId
                ? { ...m, content: m.content || finalContent, pending: false }
                : m,
            ),
          )
          if (response.toolExecutions?.length) {
            setToolExecutions(response.toolExecutions)
          }
          setActiveRunId(response.runId)
          onRunComplete(response.runId)
          setRunning(false)
          abortRef.current = null
        }
      },
      (err: string) => {
        setMessages((prev) =>
          prev.map((m) =>
            m.id === pendingId
              ? { ...m, content: `[Error: ${err}]`, pending: false }
              : m,
          ),
        )
        setRunning(false)
        abortRef.current = null
      },
    )
    abortRef.current = abort
  }, [input, running, onRunComplete])

  const handleInterrupt = useCallback(() => {
    abortRef.current?.()
    if (activeRunId) interrupt(activeRunId)
    setRunning(false)
  }, [activeRunId])

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault()
        handleSend()
      }
    },
    [handleSend],
  )

  return (
    <div className="chat">
      <div className="chat-messages">
        {messages.length === 0 && (
          <div className="chat-empty">
            <p>发送消息开始对话</p>
          </div>
        )}
        {messages.map((msg) => (
          <MessageBubble key={msg.id} message={msg} />
        ))}
        <div ref={bottomRef} />
      </div>

      {toolExecutions.length > 0 && (
        <ToolEventArea executions={toolExecutions} />
      )}

      <div className="chat-input-area">
        <textarea
          className="chat-input"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="输入消息... (Enter 发送, Shift+Enter 换行)"
          rows={3}
          disabled={running}
        />
        <div className="chat-actions">
          {running ? (
            <button className="btn btn-stop" onClick={handleInterrupt}>
              停止
            </button>
          ) : (
            <button
              className="btn btn-send"
              onClick={handleSend}
              disabled={!input.trim()}
            >
              发送
            </button>
          )}
        </div>
      </div>
    </div>
  )
}
