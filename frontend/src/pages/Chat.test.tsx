import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import * as client from '../api/client'
import type { ChatStreamHandlers } from '../api/client'
import { Chat } from './Chat'

vi.mock('../api/client')

const mockedStreamChat = vi.mocked(client.streamChat)

afterEach(() => {
  vi.resetAllMocks()
})

describe('Chat', () => {
  it('renders the empty state with no messages sent yet', () => {
    render(<Chat />)
    expect(screen.getByPlaceholderText('Ask a question…')).toBeInTheDocument()
    expect(screen.queryByText('Thinking…')).not.toBeInTheDocument()
  })

  it('streams tokens into the assistant bubble and shows source citations', async () => {
    mockedStreamChat.mockImplementation(async (_message: string, handlers: ChatStreamHandlers) => {
      handlers.onSources([
        { doc_id: 'rfc-001-payment-idempotency', title: 'RFC-001: Payment Idempotency', snippet: null, score: 0.9 },
      ])
      handlers.onToken('Duplicate charges ')
      handlers.onToken('are caused by a race condition.')
    })

    render(<Chat />)

    await userEvent.type(screen.getByPlaceholderText('Ask a question…'), 'why duplicate charges?')
    await userEvent.click(screen.getByRole('button', { name: 'Send' }))

    expect(await screen.findByText('why duplicate charges?')).toBeInTheDocument()
    expect(await screen.findByText('Duplicate charges are caused by a race condition.')).toBeInTheDocument()
    expect(screen.getByText('RFC-001: Payment Idempotency')).toBeInTheDocument()
    expect(mockedStreamChat).toHaveBeenCalledWith('why duplicate charges?', expect.anything())
  })

  it('shows an error banner if streaming fails', async () => {
    mockedStreamChat.mockRejectedValue(new Error('500 Internal Server Error'))

    render(<Chat />)
    await userEvent.type(screen.getByPlaceholderText('Ask a question…'), 'hello')
    await userEvent.click(screen.getByRole('button', { name: 'Send' }))

    await waitFor(() => expect(screen.getByText(/500 Internal Server Error/)).toBeInTheDocument())
  })
})
