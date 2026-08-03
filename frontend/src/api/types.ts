export type TicketStatus =
  | 'OPEN'
  | 'PENDING_REVIEW'
  | 'APPROVED'
  | 'REJECTED'
  | 'RESOLVED'
  | 'DISCARDED'

export interface TicketSummary {
  id: string
  incident_id: string
  status: TicketStatus
  predicted_team: string | null
  predicted_category: string | null
  predicted_severity: string | null
  confidence_score: number | null
  decision: string | null
  created_at: string
  updated_at: string
}

export interface IncidentSummary {
  id: string
  summary: string | null
  severity: string | null
  component: string | null
  source: string | null
  received_at: string
}

export interface RetrievedDoc {
  doc_id: string | null
  title: string | null
  source_type: string | null
  score: number | null
  snippet: string | null
  rank: number | null
}

export interface TicketDetail {
  id: string
  status: TicketStatus
  predicted_team: string | null
  predicted_category: string | null
  predicted_severity: string | null
  confidence_score: number | null
  decision: string | null
  rationale: string | null
  redacted_summary: string | null
  reviewed_by: string | null
  reviewed_at: string | null
  created_at: string
  updated_at: string
  incident: IncidentSummary
  retrieved_docs: RetrievedDoc[]
}

export interface Page<T> {
  content: T[]
  total_elements: number
  total_pages: number
  number: number
  size: number
  first: boolean
  last: boolean
  empty: boolean
}

export interface ChatSource {
  doc_id: string | null
  title: string | null
  snippet: string | null
  score: number | null
}

export interface ChatReply {
  answer: string
  sources: ChatSource[]
}
