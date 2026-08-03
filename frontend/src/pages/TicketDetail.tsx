import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { approveTicket, getTicket, rejectTicket } from '../api/client'
import type { TicketDetail as TicketDetailType } from '../api/types'
import { ConfidencePill, SeverityBadge, StatusBadge } from '../components/StatusBadge'

export function TicketDetail() {
  const { id } = useParams<{ id: string }>()
  const [ticket, setTicket] = useState<TicketDetailType | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [reviewedBy, setReviewedBy] = useState('')
  const [submitting, setSubmitting] = useState(false)

  function load() {
    if (!id) return
    setLoading(true)
    getTicket(id)
      .then(setTicket)
      .catch((e) => setError(String(e)))
      .finally(() => setLoading(false))
  }

  useEffect(load, [id])

  async function handleReview(action: 'approve' | 'reject') {
    if (!id) return
    setSubmitting(true)
    setError(null)
    try {
      const updated = action === 'approve' ? await approveTicket(id, reviewedBy) : await rejectTicket(id, reviewedBy)
      setTicket(updated)
    } catch (e) {
      setError(String(e))
    } finally {
      setSubmitting(false)
    }
  }

  if (loading) return <div className="page empty-state">Loading…</div>
  if (error && !ticket) return <div className="page error-banner">{error}</div>
  if (!ticket) return null

  return (
    <div className="page">
      <Link to="/" className="back-link">
        ← Back to dashboard
      </Link>

      <div className="page-header">
        <h1>Ticket {ticket.id.slice(0, 8)}</h1>
        <StatusBadge status={ticket.status} />
      </div>

      <div className="detail-grid">
        <section className="card">
          <h2>Prediction</h2>
          <dl className="kv">
            <dt>Team</dt>
            <dd>{ticket.predicted_team ?? '—'}</dd>
            <dt>Category</dt>
            <dd>{ticket.predicted_category ?? '—'}</dd>
            <dt>Severity</dt>
            <dd>
              <SeverityBadge severity={ticket.predicted_severity} />
            </dd>
            <dt>Confidence</dt>
            <dd>
              <ConfidencePill confidence={ticket.confidence_score} />
            </dd>
            <dt>Decision</dt>
            <dd>{ticket.decision ?? '—'}</dd>
          </dl>
        </section>

        <section className="card">
          <h2>Incident</h2>
          <dl className="kv">
            <dt>Summary</dt>
            <dd>{ticket.incident.summary ?? '—'}</dd>
            <dt>Source</dt>
            <dd>{ticket.incident.source ?? '—'}</dd>
            <dt>Component</dt>
            <dd>{ticket.incident.component ?? '—'}</dd>
            <dt>Received</dt>
            <dd>{new Date(ticket.incident.received_at).toLocaleString()}</dd>
          </dl>
        </section>

        <section className="card card-wide">
          <h2>Rationale</h2>
          <p className="rationale">{ticket.rationale ?? '—'}</p>
        </section>

        <section className="card card-wide">
          <h2>Redacted summary sent to the LLM</h2>
          <pre className="redacted-summary">{ticket.redacted_summary ?? '—'}</pre>
        </section>

        <section className="card card-wide">
          <h2>Retrieved doc citations</h2>
          {ticket.retrieved_docs.length === 0 ? (
            <p className="muted">No documents retrieved.</p>
          ) : (
            <ol className="citation-list">
              {ticket.retrieved_docs.map((doc, i) => (
                <li key={i} className="citation">
                  <div className="citation-header">
                    <span className="citation-title">{doc.title ?? doc.doc_id}</span>
                    <span className="citation-meta">
                      {doc.source_type} · score {doc.score?.toFixed(3) ?? '—'}
                    </span>
                  </div>
                  <p className="citation-snippet">{doc.snippet}</p>
                </li>
              ))}
            </ol>
          )}
        </section>

        {ticket.status === 'PENDING_REVIEW' && (
          <section className="card card-wide review-card">
            <h2>Human review</h2>
            <div className="review-form">
              <input
                type="text"
                placeholder="Your name (reviewed_by)"
                value={reviewedBy}
                onChange={(e) => setReviewedBy(e.target.value)}
              />
              <button className="btn btn-approve" disabled={submitting} onClick={() => handleReview('approve')}>
                Approve
              </button>
              <button className="btn btn-reject" disabled={submitting} onClick={() => handleReview('reject')}>
                Reject
              </button>
            </div>
            {error && <div className="error-banner">{error}</div>}
          </section>
        )}

        {ticket.reviewed_by && (
          <section className="card card-wide">
            <h2>Review</h2>
            <p>
              Reviewed by <strong>{ticket.reviewed_by}</strong> at{' '}
              {ticket.reviewed_at ? new Date(ticket.reviewed_at).toLocaleString() : '—'}
            </p>
          </section>
        )}
      </div>
    </div>
  )
}
