import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import * as client from '../api/client'
import type { TicketDetail as TicketDetailType } from '../api/types'
import { TicketDetail } from './TicketDetail'

vi.mock('../api/client')

const mockedGetTicket = vi.mocked(client.getTicket)
const mockedApproveTicket = vi.mocked(client.approveTicket)

afterEach(() => {
  vi.resetAllMocks()
})

function baseTicket(overrides: Partial<TicketDetailType> = {}): TicketDetailType {
  return {
    id: 'ticket-1',
    status: 'OPEN',
    predicted_team: 'payment-service',
    predicted_category: '5xx-spike',
    predicted_severity: 'critical',
    confidence_score: 0.95,
    decision: 'AUTO_TICKET',
    rationale: 'Error rate matches the known 5xx-spike pattern.',
    redacted_summary: 'payment-service returning elevated 5xx errors',
    reviewed_by: null,
    reviewed_at: null,
    created_at: '2026-01-01T00:00:00Z',
    updated_at: '2026-01-01T00:00:00Z',
    incident: {
      id: 'incident-1',
      summary: 'payment-service returning elevated 5xx errors',
      severity: 'critical',
      component: 'payment-service',
      source: 'payment-service-prod-1',
      received_at: '2026-01-01T00:00:00Z',
    },
    retrieved_docs: [
      { doc_id: '5xx-spike', title: 'PaymentService5xxSpike', source_type: 'alert', score: 0.9, snippet: 'Fires when...', rank: 1 },
    ],
    ...overrides,
  }
}

function renderTicketDetail() {
  return render(
    <MemoryRouter initialEntries={['/tickets/ticket-1']}>
      <Routes>
        <Route path="/tickets/:id" element={<TicketDetail />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('TicketDetail', () => {
  it('renders prediction fields and citations', async () => {
    mockedGetTicket.mockResolvedValue(baseTicket())

    renderTicketDetail()

    expect(await screen.findByText('5xx-spike')).toBeInTheDocument()
    expect(screen.getByText('PaymentService5xxSpike')).toBeInTheDocument()
    expect(screen.getByText(/Error rate matches the known 5xx-spike pattern/)).toBeInTheDocument()
  })

  it('does not show the review form for an already-decided ticket', async () => {
    mockedGetTicket.mockResolvedValue(baseTicket({ status: 'OPEN' }))

    renderTicketDetail()
    await screen.findByText('5xx-spike')

    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
  })

  it('shows the review form and approves a PENDING_REVIEW ticket', async () => {
    mockedGetTicket.mockResolvedValue(baseTicket({ status: 'PENDING_REVIEW' }))
    mockedApproveTicket.mockResolvedValue(
      baseTicket({ status: 'APPROVED', reviewed_by: 'alice', reviewed_at: '2026-01-01T01:00:00Z' }),
    )

    renderTicketDetail()
    await screen.findByRole('button', { name: 'Approve' })

    await userEvent.type(screen.getByPlaceholderText('Your name (reviewed_by)'), 'alice')
    await userEvent.click(screen.getByRole('button', { name: 'Approve' }))

    await waitFor(() => expect(mockedApproveTicket).toHaveBeenCalledWith('ticket-1', 'alice'))
    expect(await screen.findByText(/Reviewed by/)).toBeInTheDocument()
    expect(screen.getByText('alice')).toBeInTheDocument()
  })
})
