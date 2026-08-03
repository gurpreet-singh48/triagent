import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import * as client from '../api/client'
import { Dashboard } from './Dashboard'

vi.mock('../api/client')

const mockedListTickets = vi.mocked(client.listTickets)
const mockedTriggerSampleIncident = vi.mocked(client.triggerSampleIncident)

afterEach(() => {
  vi.resetAllMocks()
})

function renderDashboard() {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <Routes>
        <Route path="/" element={<Dashboard />} />
        <Route path="/tickets/:id" element={<div>Ticket detail page</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('Dashboard', () => {
  it('renders ticket rows from the API', async () => {
    mockedListTickets.mockResolvedValue({
      content: [
        {
          id: 'ticket-1',
          incident_id: 'incident-1',
          status: 'OPEN',
          predicted_team: 'payment-service',
          predicted_category: '5xx-spike',
          predicted_severity: 'critical',
          confidence_score: 0.95,
          decision: 'AUTO_TICKET',
          created_at: '2026-01-01T00:00:00Z',
          updated_at: '2026-01-01T00:00:00Z',
        },
      ],
      total_elements: 1,
      total_pages: 1,
      number: 0,
      size: 20,
      first: true,
      last: true,
      empty: false,
    })

    renderDashboard()

    expect(await screen.findByText('payment-service')).toBeInTheDocument()
    expect(screen.getByText('5xx-spike')).toBeInTheDocument()
  })

  it('shows an empty state when there are no tickets', async () => {
    mockedListTickets.mockResolvedValue({
      content: [], total_elements: 0, total_pages: 0, number: 0, size: 20, first: true, last: true, empty: true,
    })

    renderDashboard()

    expect(await screen.findByText('No tickets match these filters.')).toBeInTheDocument()
  })

  it('navigates to the new ticket after triggering a sample incident', async () => {
    mockedListTickets.mockResolvedValue({
      content: [], total_elements: 0, total_pages: 0, number: 0, size: 20, first: true, last: true, empty: true,
    })
    mockedTriggerSampleIncident.mockResolvedValue({
      status: 'processing', incident_id: 'incident-99', ticket_id: 'ticket-99',
    })

    renderDashboard()
    await screen.findByText('No tickets match these filters.')

    await userEvent.click(screen.getByRole('button', { name: 'Trigger Sample Incident' }))

    await waitFor(() => expect(screen.getByText('Ticket detail page')).toBeInTheDocument())
  })

  it('surfaces an error banner if the API call fails', async () => {
    mockedListTickets.mockRejectedValue(new Error('500 Internal Server Error'))

    renderDashboard()

    expect(await screen.findByText(/500 Internal Server Error/)).toBeInTheDocument()
  })
})
