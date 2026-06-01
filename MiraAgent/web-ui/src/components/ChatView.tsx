import { useEffect, useRef, useState, type KeyboardEvent } from 'react'
import type { CharacterCard, DocumentInfo, Message, StreamEvent, ToolInfo, TraceEvent } from '../types'
import { documentDownloadUrl, getCharacters, getMessages, getSessionTrace, getTools, interrupt, streamChat, uploadDocument } from '../api'
import { registerSession } from '../sessionStore'
import MessageBubble from './MessageBubble'
import ToolChip from './ToolChip'
import TracePanel from './TracePanel'
import './ChatView.css'

const IMAGE_EXT = /\.(png|jpe?g|gif|webp|bmp)$/i
const isImageName = (name: string) => IMAGE_EXT.test(name)

interface Props {
  sessionId: string
  userId: string
}

export default function ChatView({ sessionId, userId }: Props) {
  const [messages, setMessages] = useState<Message[]>([])
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  const [assistant, setAssistant] = useState('')
  const [traces, setTraces] = useState<TraceEvent[]>([])
  const [traceOpen, setTraceOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [tools, setTools] = useState<ToolInfo[]>([])
  const [enabled, setEnabled] = useState<Set<string>>(new Set())
  const [toolsOpen, setToolsOpen] = useState(false)
  const [characters, setCharacters] = useState<CharacterCard[]>([])
  const [characterId, setCharacterId] = useState<string>('mira')
  const [attachments, setAttachments] = useState<DocumentInfo[]>([])
  const [uploading, setUploading] = useState(false)
  const runIdRef = useRef<string | null>(null)
  const abortRef = useRef<null | (() => void)>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const accRef = useRef('')
  const fileInputRef = useRef<HTMLInputElement>(null)

  // 加载工具与角色（一次）。默认启用 LOW/MEDIUM 工具，HIGH/CRITICAL 默认关闭（与权限门控一致）。
  useEffect(() => {
    getTools()
      .then((t) => {
        setTools(t)
        setEnabled(new Set(t.filter((x) => x.riskLevel === 'LOW' || x.riskLevel === 'MEDIUM').map((x) => x.name)))
      })
      .catch(() => {})
    getCharacters()
      .then((cs) => {
        setCharacters(cs)
        if (cs.length && !cs.some((c) => c.id === 'mira')) setCharacterId(cs[0].id)
      })
      .catch(() => {})
  }, [])

  useEffect(() => {
    setError(null)
    setAssistant('')
    setTraces([])
    getMessages(sessionId)
      .then((m) => setMessages(m.filter((x) => x.role !== 'system')))
      .catch(() => setMessages([]))
    // 历史会话回看：回填该会话的 trace
    getSessionTrace(sessionId)
      .then((t) => setTraces(t))
      .catch(() => {})
  }, [sessionId])

  useEffect(() => {
    const el = scrollRef.current
    if (el) el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
  }, [messages, assistant])

  function toggleTool(name: string) {
    setEnabled((prev) => {
      const next = new Set(prev)
      if (next.has(name)) next.delete(name)
      else next.add(name)
      return next
    })
  }

  async function onPickFiles(files: FileList | null) {
    if (!files || files.length === 0) return
    setUploading(true)
    setError(null)
    try {
      for (const f of Array.from(files)) {
        const info = await uploadDocument(f)
        setAttachments((prev) => [...prev.filter((p) => p.name !== info.name), info])
      }
    } catch (e) {
      setError(`上传失败：${String(e)}`)
    } finally {
      setUploading(false)
      if (fileInputRef.current) fileInputRef.current.value = ''
    }
  }

  function removeAttachment(name: string) {
    setAttachments((prev) => prev.filter((p) => p.name !== name))
  }

  function finalize(text: string) {
    if (text.trim()) {
      setMessages((m) => [
        ...m,
        { id: crypto.randomUUID(), role: 'assistant', content: text, createdAt: new Date().toISOString() },
      ])
    }
    setAssistant('')
    setStreaming(false)
    abortRef.current = null
  }

  function send() {
    const typed = input.trim()
    if ((!typed && attachments.length === 0) || streaming) return
    setError(null)

    // 区分图片与文档：图片内联给多模态模型；文档走 document_read 工具。
    const imageNames = attachments.filter((a) => isImageName(a.name)).map((a) => a.name)
    const docNames = attachments.filter((a) => !isImageName(a.name)).map((a) => a.name)

    const imgNote = imageNames.map((n) => `[图片：${n}]`).join('')
    const docNote = docNames.length > 0 ? `[已上传文档：${docNames.join('、')}]\n` : ''
    const fallback = imageNames.length > 0 ? '看看这张图片。' : '请处理我上传的文档。'
    const content = imgNote + docNote + (typed || fallback)

    const sendTools = new Set(enabled)
    if (docNames.length > 0) {
      sendTools.add('document_read')
      sendTools.add('document_list')
      sendTools.add('document_write')
    }

    registerSession(sessionId, messages.length === 0 ? content : '')

    const userMsg: Message = {
      id: crypto.randomUUID(),
      role: 'user',
      content,
      createdAt: new Date().toISOString(),
    }
    setMessages((m) => [...m, userMsg])
    setInput('')
    setAttachments([])
    setAssistant('')
    setStreaming(true)
    accRef.current = ''

    abortRef.current = streamChat(
      { userId, sessionId, characterId, content, enabledTools: Array.from(sendTools), images: imageNames },
      (ev: StreamEvent) => {
        if (ev.type === 'start') {
          runIdRef.current = ev.runId
        } else if (ev.type === 'text_delta') {
          accRef.current += ev.text
          setAssistant(accRef.current)
        } else if (ev.type === 'tool_call') {
          setMessages((m) => [
            ...m,
            {
              id: crypto.randomUUID(),
              role: 'tool',
              content: null,
              toolCallId: ev.toolCall.id,
              toolName: ev.toolCall.name,
              createdAt: new Date().toISOString(),
            },
          ])
        } else if (ev.type === 'tool_result') {
          setMessages((m) =>
            m.map((x) =>
              x.role === 'tool' && x.toolCallId === ev.toolResult.toolCallId
                ? { ...x, content: ev.toolResult.content, toolName: ev.toolResult.toolName }
                : x,
            ),
          )
        } else if (ev.type === 'trace') {
          setTraces((t) => [...t, ev.trace])
        } else if (ev.type === 'done') {
          finalize(ev.response.finalMessage?.content ?? accRef.current)
        } else if (ev.type === 'error') {
          setError(ev.message)
          setStreaming(false)
        }
      },
      (err) => {
        setError(err)
        setStreaming(false)
      },
    )
  }

  function stop() {
    abortRef.current?.()
    abortRef.current = null
    const rid = runIdRef.current
    if (rid) interrupt(rid).catch(() => {})
    finalize(accRef.current)
  }

  function onKey(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      send()
    }
  }

  const empty = messages.length === 0 && !streaming
  const riskClass = (r: string) => (r === 'HIGH' || r === 'CRITICAL' ? 'risk-high' : r === 'MEDIUM' ? 'risk-med' : 'risk-low')

  return (
    <div className="chat glass">
      <header className="chat-head">
        <div className="chat-head-l">
          <span className="chat-title">对话</span>
          {characters.length > 0 && (
            <select
              className="char-select"
              value={characterId}
              onChange={(e) => setCharacterId(e.target.value)}
              disabled={streaming}
              title="选择角色"
            >
              {characters.map((c) => (
                <option key={c.id} value={c.id}>{c.name}</option>
              ))}
            </select>
          )}
          <span className="chat-sid mono">{sessionId}</span>
        </div>
        <div className="chat-head-r">
          <button className={`btn btn-ghost tools-btn ${toolsOpen ? 'on' : ''}`} onClick={() => setToolsOpen((v) => !v)}>
            工具 <span className="trace-count">{enabled.size}</span>
          </button>
          <button
            className={`btn btn-ghost trace-btn ${traceOpen ? 'on' : ''}`}
            onClick={() => setTraceOpen((v) => !v)}
          >
            <span className="dot" style={{ background: traces.length ? 'var(--accent)' : 'var(--text-faint)', boxShadow: 'none' }} />
            Trace
            {traces.length > 0 && <span className="trace-count">{traces.length}</span>}
          </button>
        </div>
      </header>

      {toolsOpen && (
        <div className="tools-bar">
          {tools.length === 0 && <span className="tools-empty">无可用工具</span>}
          {tools.map((t) => (
            <button
              key={t.name}
              className={`tool-toggle ${enabled.has(t.name) ? 'active' : ''} ${riskClass(t.riskLevel)}`}
              onClick={() => toggleTool(t.name)}
              title={`${t.description}\n风险: ${t.riskLevel}`}
            >
              {t.name}
            </button>
          ))}
        </div>
      )}

      <div className="chat-scroll" ref={scrollRef}>
        {empty ? (
          <div className="chat-empty">
            <div className="empty-orb" />
            <h2>开始一段对话</h2>
            <p>这是有状态、可调用工具的陪伴型 Agent。试着让它帮你记一条 todo 或算一道题。</p>
            <div className="empty-hints">
              <span className="pill">记一条待办：明天读论文</span>
              <span className="pill">帮我算 (3+4)*2</span>
            </div>
          </div>
        ) : (
          <div className="chat-thread">
            {messages.map((m) => {
              if (m.role === 'tool') return <ToolChip key={m.id} name={m.toolName ?? 'tool'} content={m.content} />
              if (m.role === 'system') return null
              // 只带工具调用、无文字的 assistant 轮次不渲染空气泡（工具 chip 已表达该轮）
              if (m.role === 'assistant' && !(m.content && m.content.trim())) return null
              return <MessageBubble key={m.id} role={m.role} content={m.content ?? ''} />
            })}
            {streaming && (
              <MessageBubble role="assistant" content={assistant} pending />
            )}
          </div>
        )}
      </div>

      {error && <div className="chat-error">⚠ {error}</div>}

      {attachments.length > 0 && (
        <div className="attach-bar">
          {attachments.map((a) =>
            isImageName(a.name) ? (
              <span key={a.name} className="attach-img" title={a.name}>
                <img src={documentDownloadUrl(a.name)} alt={a.name} />
                <button className="attach-x attach-x-img" onClick={() => removeAttachment(a.name)} title="移除">×</button>
              </span>
            ) : (
              <span key={a.name} className="attach-chip" title={a.name}>
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" width="13" height="13">
                  <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8l-5-5Z" strokeLinejoin="round" />
                  <path d="M14 3v5h5" strokeLinejoin="round" />
                </svg>
                <span className="attach-name">{a.name}</span>
                <button className="attach-x" onClick={() => removeAttachment(a.name)} title="移除">×</button>
              </span>
            ),
          )}
        </div>
      )}

      <div className="composer">
        <input
          ref={fileInputRef}
          type="file"
          multiple
          hidden
          onChange={(e) => void onPickFiles(e.target.files)}
        />
        <button
          className="attach-btn"
          onClick={() => fileInputRef.current?.click()}
          disabled={streaming || uploading}
          title="上传图片或文档（图片走视觉理解，文档走解析）"
        >
          {uploading ? (
            <span className="attach-spin" />
          ) : (
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" width="19" height="19">
              <path d="M21.4 11.05 12.25 20.2a5 5 0 0 1-7.07-7.07l9.2-9.2a3.33 3.33 0 1 1 4.71 4.71l-9.2 9.2a1.67 1.67 0 0 1-2.36-2.36l8.49-8.48" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          )}
        </button>
        <textarea
          className="composer-input"
          placeholder="说点什么…（Enter 发送，Shift+Enter 换行）"
          value={input}
          rows={1}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={onKey}
        />
        {streaming ? (
          <button className="btn btn-danger send-btn" onClick={stop}>停止</button>
        ) : (
          <button className="btn btn-accent send-btn" onClick={send} disabled={!input.trim() && attachments.length === 0}>
            发送
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" width="16" height="16">
              <path d="M5 12h14M13 6l6 6-6 6" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </button>
        )}
      </div>

      <TracePanel open={traceOpen} traces={traces} onClose={() => setTraceOpen(false)} />
    </div>
  )
}
