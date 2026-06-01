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
  const isUser = role === 'user'
  const hasContent = content.length > 0
  const { images, text: userText } = isUser ? splitImages(content) : { images: [], text: content }

  if (isUser && images.length > 0) {
    return (
      <div className="row row-user">
        <div className="bubble bubble-user">
          <div className="bubble-imgs">
            {images.map((name) => (
              <a key={name} href={documentDownloadUrl(name)} target="_blank" rel="noreferrer" title={name}>
                <img className="bubble-img" src={documentDownloadUrl(name)} alt={name} />
              </a>
            ))}
          </div>
          {userText && <p className="bubble-text">{userText}</p>}
        </div>
      </div>
    )
  }

  return (
    <div className={`row ${isUser ? 'row-user' : 'row-bot'}`}>
      {!isUser && (
        <div className="avatar">
          <span />
        </div>
      )}
      <div className={`bubble ${isUser ? 'bubble-user' : 'bubble-bot'}`}>
        {hasContent ? (
          isUser ? (
            <p className="bubble-text">{content}</p>
          ) : (
            <div className="bubble-md">
              <ReactMarkdown remarkPlugins={[remarkGfm]}>{content}</ReactMarkdown>
              {pending && <span className="cursor" />}
            </div>
          )
        ) : pending ? (
          <span className="thinking">
            <i /><i /><i />
          </span>
        ) : (
          <p className="bubble-text" />
        )}
      </div>
    </div>
  )
}
