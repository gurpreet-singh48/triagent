import type { ChatReply, ChatSource, Page, TicketDetail, TicketStatus, TicketSummary } from './types'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
  if (!response.ok) {
    const body = await response.text()
    throw new Error(`${response.status} ${response.statusText}: ${body}`)
  }
  return response.json() as Promise<T>
}

export interface ListTicketsParams {
  team?: string
  status?: TicketStatus
  page?: number
  size?: number
}

export function listTickets(params: ListTicketsParams = {}): Promise<Page<TicketSummary>> {
  const query = new URLSearchParams()
  if (params.team) query.set('team', params.team)
  if (params.status) query.set('status', params.status)
  query.set('page', String(params.page ?? 0))
  query.set('size', String(params.size ?? 20))
  return request(`/api/tickets?${query.toString()}`)
}

export function getTicket(id: string): Promise<TicketDetail> {
  return request(`/api/tickets/${id}`)
}

export function approveTicket(id: string, reviewedBy: string): Promise<TicketDetail> {
  return request(`/api/tickets/${id}/approve`, {
    method: 'POST',
    body: JSON.stringify({ reviewed_by: reviewedBy }),
  })
}

export function rejectTicket(id: string, reviewedBy: string): Promise<TicketDetail> {
  return request(`/api/tickets/${id}/reject`, {
    method: 'POST',
    body: JSON.stringify({ reviewed_by: reviewedBy }),
  })
}

export function postChat(message: string, conversationId?: string): Promise<ChatReply> {
  return request('/api/chat', {
    method: 'POST',
    body: JSON.stringify({ message, conversation_id: conversationId ?? null }),
  })
}

export interface ChatStreamHandlers {
  onToken: (text: string) => void
  onSources: (sources: ChatSource[]) => void
}

/**
 * POST /api/chat/stream returns Spring's SSE encoding (`event: <type>\ndata:
 * <json>\n\n`). EventSource can't be used since it only supports GET, so we
 * read the fetch response body as a stream and parse SSE frames by hand.
 */
export async function streamChat(message: string, handlers: ChatStreamHandlers, conversationId?: string): Promise<void> {
  const response = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message, conversation_id: conversationId ?? null }),
  })
  if (!response.ok || !response.body) {
    const body = await response.text()
    throw new Error(`${response.status} ${response.statusText}: ${body}`)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    let separatorIndex: number
    while ((separatorIndex = buffer.indexOf('\n\n')) !== -1) {
      const rawFrame = buffer.slice(0, separatorIndex)
      buffer = buffer.slice(separatorIndex + 2)

      let eventType = 'message'
      const dataLines: string[] = []
      for (const line of rawFrame.split('\n')) {
        if (line.startsWith('event:')) eventType = line.slice(6).trim()
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
      }
      const data = dataLines.join('\n')
      if (!data) continue

      if (eventType === 'token') {
        handlers.onToken((JSON.parse(data) as { text: string }).text)
      } else if (eventType === 'sources') {
        handlers.onSources((JSON.parse(data) as { sources: ChatSource[] }).sources)
      }
    }
  }
}

export interface SampleIncidentResponse {
  status: string
  incident_id: string
  ticket_id: string | null
}

export function triggerSampleIncident(): Promise<SampleIncidentResponse> {
  return request('/api/dev/sample-incidents', { method: 'POST' })
}
