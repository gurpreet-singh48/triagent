import { useState } from 'react'
import { streamChat } from '../api/client'
import type { ChatSource } from '../api/types'

interface Turn {
  role: 'user' | 'assistant'
  text: string
  sources?: ChatSource[]
}

export function Chat() {
  const [turns, setTurns] = useState<Turn[]>([])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleSend() {
    const message = input.trim()
    if (!message || loading) return
    setInput('')
    setError(null)
    setTurns((t) => [...t, { role: 'user', text: message }, { role: 'assistant', text: '' }])
    setLoading(true)
    try {
      await streamChat(message, {
        onToken: (delta) => {
          setTurns((t) => {
            const next = [...t]
            const last = next[next.length - 1]
            next[next.length - 1] = { ...last, text: last.text + delta }
            return next
          })
        },
        onSources: (sources) => {
          setTurns((t) => {
            const next = [...t]
            next[next.length - 1] = { ...next[next.length - 1], sources }
            return next
          })
        },
      })
    } catch (e) {
      setError(String(e))
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <h1>Ask about the platform</h1>
      </div>
      <p className="muted">
        RAG chat over the doc corpus (RFCs, alert rules, Go service source). Try:{' '}
        <em>"what causes payment service duplicate charges"</em>.
      </p>

      <div className="chat-thread">
        {turns.map((turn, i) => {
          const isPendingAssistant = turn.role === 'assistant' && turn.text === '' && loading && i === turns.length - 1
          return (
            <div
              key={i}
              className={`chat-turn chat-turn-${turn.role}${isPendingAssistant ? ' chat-turn-loading' : ''}`}
            >
              <div className="chat-bubble">{isPendingAssistant ? 'Thinking…' : turn.text}</div>
              {turn.sources && turn.sources.length > 0 && (
                <div className="chat-sources">
                  {turn.sources.map((s, j) => (
                    <div key={j} className="chat-source">
                      <span className="citation-title">{s.title ?? s.doc_id}</span>
                      {s.score !== null && <span className="citation-meta"> · score {s.score.toFixed(3)}</span>}
                    </div>
                  ))}
                </div>
              )}
            </div>
          )
        })}
      </div>

      {error && <div className="error-banner">{error}</div>}

      <div className="chat-input-row">
        <input
          type="text"
          placeholder="Ask a question…"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') handleSend()
          }}
        />
        <button className="btn btn-primary" onClick={handleSend} disabled={loading}>
          Send
        </button>
      </div>
    </div>
  )
}
