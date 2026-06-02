import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { documentDownloadUrl } from '../api'
import './MessageBubble.css'

interface Props {
  role: 'user' | 'assistant'
  content: string
  pending?: boolean
}

const IMG_MARKER = /\[图片：([^\]]+)\]/g
/** AI 主动分条发送的强分隔标记。 */
const BREAK = '[[break]]'

/** 含 markdown 块结构（代码块/列表/标题/表格/引用）时不按换行拆，避免拆坏排版。 */
function hasMarkdownBlock(s: string): boolean {
  return /```|^[ \t]*([-*+]|\d+\.)[ \t]+|^[ \t]*#{1,6}[ \t]+|^[ \t]*>[ \t]?|^.*\|.*\|/m.test(s)
}

/**
 * 把 assistant 文本切成多个气泡：连发只认 [[break]] 强分隔。
 * 每段若是纯文本，仅按空行(段落)再拆——单换行(如逐行列点)留在同一条气泡，
 * 避免一份本该整体阅读的清单被打散成一堆；含 markdown 块则整段保留。
 */
function toBubbles(content: string): string[] {
  const out: string[] = []
  for (const block of content.split(BREAK)) {
    const t = block.trim()
    if (!t) continue
    if (hasMarkdownBlock(t)) {
      out.push(t)
    } else {
      for (const para of t.split(/\n{2,}/)) {
        const pt = para.trim()
        if (pt) out.push(pt)
      }
    }
  }
  return out
}

/** 从用户消息里抽出 [图片：name] 标记，返回图片名列表与去掉标记后的文字。 */
function splitImages(content: string): { images: string[]; text: string } {
  const images: string[] = []
  let m: RegExpExecArray | null
  IMG_MARKER.lastIndex = 0
  while ((m = IMG_MARKER.exec(content))) images.push(m[1])
  const text = content.replace(IMG_MARKER, '').trim()
  return { images, text }
}

export default function MessageBubble({ role, content, pending }: Props) {
  if (role === 'user') {
    const { images, text } = splitImages(content)
    return (
      <div className="row row-user">
        <div className="bubble bubble-user">
          {images.length > 0 && (
            <div className="bubble-imgs">
              {images.map((name) => (
                <a key={name} href={documentDownloadUrl(name)} target="_blank" rel="noreferrer" title={name}>
                  <img className="bubble-img" src={documentDownloadUrl(name)} alt={name} />
                </a>
              ))}
            </div>
          )}
          {text && <p className="bubble-text">{text}</p>}
        </div>
      </div>
    )
  }

  // assistant：拆成多个连续气泡，模拟真人连发短消息
  const bubbles = toBubbles(content)

  return (
    <div className="row row-bot">
      <div className="avatar">
        <img src="/mira-logo.png" alt="Mira" className="avatar-logo" />
      </div>
      {bubbles.length > 0 ? (
        <div className="bubble-stack">
          {bubbles.map((seg, i) => (
            <div className="bubble bubble-bot" key={i}>
              <div className="bubble-md">
                <ReactMarkdown remarkPlugins={[remarkGfm]}>{seg}</ReactMarkdown>
                {pending && i === bubbles.length - 1 && <span className="cursor" />}
              </div>
            </div>
          ))}
        </div>
      ) : (
        <div className="bubble bubble-bot">
          {pending ? (
            <span className="thinking"><i /><i /><i /></span>
          ) : (
            <p className="bubble-text" />
          )}
        </div>
      )}
    </div>
  )
}
