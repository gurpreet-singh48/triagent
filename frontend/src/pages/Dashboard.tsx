import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { listTickets, triggerSampleIncident } from '../api/client'
import type { Page, TicketStatus, TicketSummary } from '../api/types'
import { ConfidencePill, StatusBadge } from '../components/StatusBadge'

const TEAMS = ['payment-service', 'auth-service', 'queue-consumer']
const STATUSES: TicketStatus[] = ['OPEN', 'PENDING_REVIEW', 'APPROVED', 'REJECTED', 'RESOLVED', 'DISCARDED']

export function Dashboard() {
  const navigate = useNavigate()
  const [team, setTeam] = useState('')
  const [status, setStatus] = useState<TicketStatus | ''>('')
  const [page, setPage] = useState(0)
  const [data, setData] = useState<Page<TicketSummary> | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [triggering, setTriggering] = useState(false)

  function load() {
    setLoading(true)
    setError(null)
    listTickets({ team: team || undefined, status: status || undefined, page })
      .then(setData)
      .catch((e) => setError(String(e)))
      .finally(() => setLoading(false))
  }

  useEffect(load, [team, status, page])

  async function handleTriggerSample() {
    setTriggering(true)
    setError(null)
    try {
      const result = await triggerSampleIncident()
      if (result.ticket_id) {
        navigate(`/tickets/${result.ticket_id}`)
      } else {
        load()
      }
    } catch (e) {
      setError(String(e))
    } finally {
      setTriggering(false)
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <h1>Tickets</h1>
        <button className="btn btn-primary" onClick={handleTriggerSample} disabled={triggering}>
          {triggering ? 'Triggering…' : 'Trigger Sample Incident'}
        </button>
      </div>

      <div className="filters">
        <select
          value={team}
          onChange={(e) => {
            setTeam(e.target.value)
            setPage(0)
          }}
        >
          <option value="">All teams</option>
          {TEAMS.map((t) => (
            <option key={t} value={t}>
              {t}
            </option>
          ))}
        </select>

        <select
          value={status}
          onChange={(e) => {
            setStatus(e.target.value as TicketStatus | '')
            setPage(0)
          }}
        >
          <option value="">All statuses</option>
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {s.replace('_', ' ')}
            </option>
          ))}
        </select>
      </div>

      {error && <div className="error-banner">{error}</div>}

      {loading ? (
        <div className="empty-state">Loading…</div>
      ) : !data || data.content.length === 0 ? (
        <div className="empty-state">No tickets match these filters.</div>
      ) : (
        <>
          <table className="ticket-table">
            <thead>
              <tr>
                <th>Status</th>
                <th>Team</th>
                <th>Category</th>
                <th>Severity</th>
                <th>Confidence</th>
                <th>Decision</th>
                <th>Created</th>
              </tr>
            </thead>
            <tbody>
              {data.content.map((ticket) => (
                <tr key={ticket.id}>
                  <td>
                    <Link to={`/tickets/${ticket.id}`} className="ticket-link">
                      <StatusBadge status={ticket.status} />
                    </Link>
                  </td>
                  <td>{ticket.predicted_team ?? '—'}</td>
                  <td>{ticket.predicted_category ?? '—'}</td>
                  <td>{ticket.predicted_severity ?? '—'}</td>
                  <td>
                    <ConfidencePill confidence={ticket.confidence_score} />
                  </td>
                  <td>{ticket.decision ?? '—'}</td>
                  <td>{new Date(ticket.created_at).toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>

          <div className="pagination">
            <button disabled={data.first} onClick={() => setPage((p) => p - 1)}>
              Previous
            </button>
            <span>
              Page {data.number + 1} of {Math.max(data.total_pages, 1)} ({data.total_elements} tickets)
            </span>
            <button disabled={data.last} onClick={() => setPage((p) => p + 1)}>
              Next
            </button>
          </div>
        </>
      )}
    </div>
  )
}
