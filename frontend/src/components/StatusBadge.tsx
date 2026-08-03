import type { TicketStatus } from '../api/types'

const STATUS_STYLES: Record<TicketStatus, string> = {
  OPEN: 'badge badge-open',
  PENDING_REVIEW: 'badge badge-pending',
  APPROVED: 'badge badge-approved',
  REJECTED: 'badge badge-rejected',
  RESOLVED: 'badge badge-resolved',
  DISCARDED: 'badge badge-discarded',
}

export function StatusBadge({ status }: { status: TicketStatus }) {
  return <span className={STATUS_STYLES[status] ?? 'badge'}>{status.replace('_', ' ')}</span>
}

export function SeverityBadge({ severity }: { severity: string | null }) {
  if (!severity) return null
  return <span className={`badge badge-severity-${severity.toLowerCase()}`}>{severity}</span>
}

export function ConfidencePill({ confidence }: { confidence: number | null }) {
  if (confidence === null) return <span className="confidence-pill confidence-unknown">—</span>
  const pct = Math.round(confidence * 100)
  const level = confidence >= 0.9 ? 'high' : confidence >= 0.6 ? 'medium' : 'low'
  return <span className={`confidence-pill confidence-${level}`}>{pct}%</span>
}
