import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import './MessageBubble.css'

interface Props {
  role: 'user' | 'assistant'
  content: string
  pending?: boolean
}

export default function MessageBubble({ role, content, pending }: Props) {
  const isUser = role === 'user'
  const hasContent = content.length > 0

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
